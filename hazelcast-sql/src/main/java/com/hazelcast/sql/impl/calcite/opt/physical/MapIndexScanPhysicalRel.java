/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.calcite.opt.physical;

import com.hazelcast.sql.impl.calcite.opt.AbstractMapScanRel;
import com.hazelcast.sql.impl.calcite.opt.cost.CostUtils;
import com.hazelcast.sql.impl.calcite.opt.physical.visitor.PhysicalRelVisitor;
import com.hazelcast.sql.impl.exec.scan.index.IndexFilter;
import com.hazelcast.sql.impl.schema.map.MapTableIndex;
import com.hazelcast.sql.impl.type.QueryDataType;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;

import java.util.List;

/**
 * Map index scan operator.
 */
public class MapIndexScanPhysicalRel extends AbstractMapScanRel implements PhysicalRel {

    private final MapTableIndex index;
    private final List<IndexFilter> indexFilters;
    private final List<QueryDataType> converterTypes;
    private final RexNode indexExp;
    private final RexNode remainderExp;

    public MapIndexScanPhysicalRel(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelOptTable table,
        MapTableIndex index,
        List<IndexFilter> indexFilters,
        List<QueryDataType> converterTypes,
        RexNode indexExp,
        RexNode remainderExp
    ) {
        super(cluster, traitSet, table);

        this.index = index;
        this.indexFilters = indexFilters;
        this.converterTypes = converterTypes;
        this.indexExp = indexExp;
        this.remainderExp = remainderExp;
    }

    public MapTableIndex getIndex() {
        return index;
    }

    public List<IndexFilter> getIndexFilters() {
        return indexFilters;
    }

    public List<QueryDataType> getConverterTypes() {
        return converterTypes;
    }

    public RexNode getRemainderExp() {
        return remainderExp;
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new MapIndexScanPhysicalRel(
            getCluster(),
            traitSet,
            getTable(),
            index,
            indexFilters,
            converterTypes,
            indexExp,
            remainderExp
        );
    }

    @Override
    public void visit(PhysicalRelVisitor visitor) {
        visitor.onMapIndexScan(this);
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        return super.explainTerms(pw)
           .item("index", index.getName())
           .item("indexExp", indexExp)
           .item("remainderExp", remainderExp);
    }

    @Override
    public double estimateRowCount(RelMetadataQuery mq) {
        double rowCount = table.getRowCount();

        if (indexExp != null) {
            rowCount = CostUtils.adjustFilteredRowCount(rowCount, RelMdUtil.guessSelectivity(indexExp));
        }

        if (remainderExp != null) {
            rowCount = CostUtils.adjustFilteredRowCount(rowCount, RelMdUtil.guessSelectivity(remainderExp));
        }

        return rowCount;
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // Get the number of rows being scanned. This is either the whole index (scan), or only part of the index (lookup)
        double scanRowCount = table.getRowCount();

        if (indexExp != null) {
            scanRowCount = CostUtils.adjustFilteredRowCount(scanRowCount, RelMdUtil.guessSelectivity(indexExp));
        }

        // Get the number of rows that we expect after the remainder filter is applied.
        boolean hasFilter = remainderExp != null;

        double filterRowCount = scanRowCount;

        if (hasFilter) {
            filterRowCount = CostUtils.adjustFilteredRowCount(filterRowCount, RelMdUtil.guessSelectivity(remainderExp));
        }

        return computeSelfCost(
            planner,
            scanRowCount,
            CostUtils.indexScanCpuMultiplier(index.getType()),
            hasFilter,
            filterRowCount,
            getTableUnwrapped().getProjects().size()
        );
    }
}
