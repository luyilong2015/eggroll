/*
 * Copyright (c) 2019 - now, Eggroll Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.webank.eggroll.rollpair.component

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.function.Supplier

import com.webank.eggroll.core.constant.StringConstants
import com.webank.eggroll.core.datastructure.{Broker, LinkedBlockingBroker}
import com.webank.eggroll.core.error.DistributedRuntimeException
import com.webank.eggroll.core.meta.MetaModelPbMessageSerdes._
import com.webank.eggroll.core.meta._
import com.webank.eggroll.core.transfer.{GrpcTransferService, TransferClient}
import com.webank.eggroll.core.util.{Logging, ThreadPoolUtils}
import com.webank.eggroll.rollpair.io.RocksdbSortedKvAdapter

import scala.collection.mutable

trait Shuffler {
  def start(): Unit
  def isFinished(): Boolean
  def waitUntilFinished(timeout: Long, unit: TimeUnit): Boolean
  def getTotalPartitionedCount(): Long
  def hasError(): Boolean
  def getError(): DistributedRuntimeException
}

object Shuffler  {
  val idPrefix = classOf[Shuffler].getSimpleName
  val idIndex = new AtomicInteger(0)
  def generateId(idPrefix: String = Shuffler.idPrefix,
                 idIndex: Int = Shuffler.idIndex.incrementAndGet()) = s"${idPrefix}-${idIndex}"
}


// todo: move calculation to threads
class DefaultShuffler(shuffleId: String,
                      map_results_broker: Broker[(Array[Byte], Array[Byte])],
                      outputStore: ErStore,
                      outputPartition: ErPartition,
                      partitionFunction: Array[Byte] => Int,
                      parallelSize: Int = 5) extends Shuffler with Logging {

  val partitionThreadPool = ThreadPoolUtils.newFixedThreadPool(parallelSize, s"${shuffleId}")
  val sendRecvThreadPool = ThreadPoolUtils.newFixedThreadPool(2, prefix = s"${shuffleId}-sendrecv")

  val outputPartitions = outputStore.partitions
  val outputPartitionsCount = outputPartitions.length

  val partitionedBrokers = for (i <- 0 until outputPartitionsCount) yield new LinkedBlockingBroker[(Array[Byte], Array[Byte])]()
  // val partitionFinishLatch = new CountDownLatch(parallelSize)
  val shuffleFinishLatch = new CountDownLatch(3)

  val notFinishedPartitionThreadCount = new CountDownLatch(parallelSize)
  val isSendFinished = new AtomicBoolean(false)
  val isRecvFinished = new AtomicBoolean(false)
  val totalPartitionedElementsCount = new AtomicLong(0L)
  val errors = new DistributedRuntimeException

  def start(): Unit = {
    GrpcTransferService.getOrCreateBroker(key = s"${shuffleId}-${outputPartition.id}", writeSignals = outputPartitionsCount)

    for (i <- 0 until parallelSize) {
      val cf: CompletableFuture[Long] =
        CompletableFuture
          .supplyAsync(
            new Partitioner(map_results_broker,
              partitionedBrokers.toList,
              partitionFunction),
            partitionThreadPool)
          .whenCompleteAsync((result, exception) => {
            if (exception == null) {
              notFinishedPartitionThreadCount.countDown()

              if (notFinishedPartitionThreadCount.getCount <= 0) {
                partitionedBrokers.foreach(b => b.signalWriteFinish())
                shuffleFinishLatch.countDown()
                logInfo(s"finished partition for partition")
              }

              totalPartitionedElementsCount.addAndGet(result)
            } else {
              logError(s"error in computing partition ${i}", exception)
              errors.append(exception)
            }
          }, partitionThreadPool)
    }

    val sender: CompletableFuture[Long] =
      CompletableFuture.supplyAsync(new ShuffleSender(shuffleId = shuffleId,
          brokers = partitionedBrokers.toArray,
          targetPartitions = outputPartitions), sendRecvThreadPool)
      .whenCompleteAsync((result, exception) => {
        if (exception == null) {
          logInfo(s"finished send. total sent: ${result}")
          isSendFinished.compareAndSet(false, true)
          shuffleFinishLatch.countDown()
        } else {
          logError(s"error in send", exception)
          errors.append(exception)
        }
      }, sendRecvThreadPool)

    val receiver: CompletableFuture[Long] =
      CompletableFuture.supplyAsync(new ShuffleReceiver(shuffleId = shuffleId,
          outputPartition = outputPartition,
          totalPartitionsCount = outputPartitionsCount), sendRecvThreadPool)
          .whenCompleteAsync((result, exception) => {
            if (exception == null) {
              isRecvFinished.compareAndSet(false, true)
              shuffleFinishLatch.countDown()
            } else {
              logError(s"error in receive", exception)
              errors.append(exception)
            }
          }, sendRecvThreadPool)


    // notFinishedPartitionThreadCount.await()
    // partitionThreadPool.shutdown()
  }

  override def isFinished(): Boolean = notFinishedPartitionThreadCount.getCount() <= 0 && isSendFinished.get() && isRecvFinished.get()

  override def waitUntilFinished(timeout: Long, unit: TimeUnit): Boolean = shuffleFinishLatch.await(timeout, unit)

  override def getTotalPartitionedCount(): Long = totalPartitionedElementsCount.get()

  override def hasError(): Boolean = errors.check()

  override def getError(): DistributedRuntimeException = errors

  class Partitioner(input: Broker[(Array[Byte], Array[Byte])],
                    output: List[Broker[(Array[Byte], Array[Byte])]],
                    partitionFunction: Array[Byte] => Int,
                   // todo: configurable
                    chunkSize: Int = 1000) extends Supplier[Long] {
    var partitionedElementsCount = 0L

    override def get(): Long = {
      val chunk = new util.ArrayList[(Array[Byte], Array[Byte])](chunkSize)
      while (!input.isClosable()) {
        chunk.clear()
        input.drainTo(chunk, chunkSize)

        chunk.forEach(t => {
          output(partitionFunction(t._1)).put(t)
          partitionedElementsCount += 1L
        })
      }

      partitionedElementsCount
    }
  }

  // todo: consider change (Array[Byte], Array[Byte]) to ErPair
  class ShuffleSender(shuffleId: String,
                      brokers: Array[Broker[(Array[Byte], Array[Byte])]],
                      targetPartitions: Array[ErPartition],
                      // todo: make it configurable
                      chunkSize: Int = 100)
    extends Supplier[Long] {

    override def get(): Long = {
      // todo: change to event-driven
      val notFinishedBrokerIndex = mutable.Set[Int]()
      for (i <- 0 until brokers.size) {
        notFinishedBrokerIndex.add(i)
      }

      val transferClient = new TransferClient()
      val newlyFinishedIdx = mutable.ListBuffer[Int]()
      var totalSent = 0L

      while (notFinishedBrokerIndex.nonEmpty) {
        notFinishedBrokerIndex.foreach(idx => {
          val sendBuffer = new util.ArrayList[(Array[Byte], Array[Byte])](chunkSize)
          val broker = brokers(idx)
          var isBrokerClosable = false

          this.synchronized {
            if (broker.isWriteFinished() || broker.size() >= chunkSize) {
              broker.drainTo(sendBuffer, chunkSize)
              isBrokerClosable = broker.isClosable()
            }
          }

          if (!sendBuffer.isEmpty) {
            val pairs = mutable.ListBuffer[ErPair]()
            sendBuffer.forEach(t => {
              pairs += ErPair(key = t._1, value = t._2)
            })

            val pairBatch = ErPairBatch(pairs = pairs.toArray)

            var transferStatus = StringConstants.EMPTY
            if (isBrokerClosable) {
              transferStatus = StringConstants.TRANSFER_END
              newlyFinishedIdx += idx
            }

            transferClient.send(data = pairBatch.toProto.toByteArray,
              tag = s"${shuffleId}-${idx}",
              serverNode = targetPartitions(idx).processor,
              status = transferStatus)
            totalSent += pairs.length
          }
        })

        newlyFinishedIdx.foreach(idx => notFinishedBrokerIndex -= idx)
        newlyFinishedIdx.clear()
      }

      totalSent
    }
  }

  class ShuffleReceiver(shuffleId: String,
                        outputPartition: ErPartition,
                        totalPartitionsCount: Int)
    extends Supplier[Long] {
    override def get(): Long = {
      var totalWrite = 0L
      val broker = GrpcTransferService.getOrCreateBroker(s"${shuffleId}-${outputPartition.id}")

      val path = EggPair.getDbPath(outputPartition)
      logInfo(s"outputPath: ${path}")
      val outputAdapter = new RocksdbSortedKvAdapter(path)

      while (!broker.isClosable()) {
        val binData = broker.poll(1, TimeUnit.SECONDS)

        if (binData != null) {
          // todo: add 'parseFromBytes' to ErPairBatch to decouple from pb
          val pbPairBatch = Meta.PairBatch.parseFrom(binData)

          val pairBatch = pbPairBatch.fromProto()

          val result = pairBatch.pairs.map(erPair => (erPair.key, erPair.value))
          logInfo(s"result: ${result}, length: ${result.length}")

          outputAdapter.writeBatch(pairBatch.pairs.map(erPair => (erPair.key, erPair.value)).iterator)

          totalWrite += pairBatch.pairs.length
        }
      }

      outputAdapter.close()

      totalWrite
    }
  }
}

