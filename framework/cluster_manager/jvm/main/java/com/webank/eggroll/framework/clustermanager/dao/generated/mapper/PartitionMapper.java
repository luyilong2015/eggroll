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

package com.webank.eggroll.framework.clustermanager.dao.generated.mapper;

import com.webank.eggroll.framework.clustermanager.dao.generated.model.Partition;
import com.webank.eggroll.framework.clustermanager.dao.generated.model.PartitionExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.RowBounds;

public interface PartitionMapper {
    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    long countByExample(PartitionExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int deleteByExample(PartitionExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int deleteByPrimaryKey(Long partitionId);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int insert(Partition record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int insertSelective(Partition record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    List<Partition> selectByExampleWithRowbounds(PartitionExample example, RowBounds rowBounds);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    List<Partition> selectByExample(PartitionExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    Partition selectByPrimaryKey(Long partitionId);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int updateByExampleSelective(@Param("record") Partition record, @Param("example") PartitionExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int updateByExample(@Param("record") Partition record, @Param("example") PartitionExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int updateByPrimaryKeySelective(Partition record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table partition
     *
     * @mbg.generated
     */
    int updateByPrimaryKey(Partition record);
}