/*
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified this file.
 *
 * All Splice Machine modifications are Copyright 2012 - 2016 Splice Machine, Inc.,
 * and are licensed to you under the License; you may not use this file except in
 * compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */


package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.sql.compile.Visitable;
import com.splicemachine.db.iapi.sql.compile.Visitor;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.util.JBitSet;

/**
 * Build a JBitSet of all of the referenced tables in the tree.
 *
 */
public class ReferencedTablesVisitor implements Visitor 
{
	private JBitSet tableMap;

	public ReferencedTablesVisitor(JBitSet tableMap)
	{
		this.tableMap = tableMap;
	}


	////////////////////////////////////////////////
	//
	// VISITOR INTERFACE
	//
	////////////////////////////////////////////////

	/**
	 * Don't do anything unless we have a ColumnReference,
	 * Predicate or ResultSetNode node.
	 *
	 * @param node 	the node to process
	 *
	 * @return me
	 *
	 * @exception StandardException on error
	 */
	public Visitable visit(Visitable node, QueryTreeNode parent)
		throws StandardException
	{
		if (node instanceof ColumnReference)
		{
			((ColumnReference)node).getTablesReferenced(tableMap);
		}
		else if (node instanceof Predicate)
		{
			Predicate pred = (Predicate) node;
			tableMap.or(pred.getReferencedSet());
		}
		else if (node instanceof ResultSetNode)
		{
			ResultSetNode rs = (ResultSetNode) node;
			tableMap.or(rs.getReferencedTableMap());
		}

		return node;
	}

	/**
	 * No need to go below a Predicate or ResultSet.
	 *
	 * @return Whether or not to go below the node.
	 */
	public boolean skipChildren(Visitable node)
	{
		return (node instanceof Predicate ||
			    node instanceof ResultSetNode);
	}

	public boolean visitChildrenFirst(Visitable node)
	{
		return false;
	}

	public boolean stopTraversal()
	{
		return false;
	}
	////////////////////////////////////////////////
	//
	// CLASS INTERFACE
	//
	////////////////////////////////////////////////
	JBitSet getTableMap()
	{
		return tableMap;
	}
}	
