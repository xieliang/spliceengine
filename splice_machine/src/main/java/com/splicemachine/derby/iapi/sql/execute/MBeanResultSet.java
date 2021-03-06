/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.derby.iapi.sql.execute;

import java.sql.SQLException;
import java.util.List;

/**
 * @author Jeff Cunningham
 *         Date: 12/10/13
 */
public class MBeanResultSet extends SpliceAbstractResultSet {
    private final List<String> columnNames;
    private List<List<String>> rows;
    private int currentIndex = -1;

    public MBeanResultSet(List<List<String>> rows, List<String> columnNames) {
        this.columnNames = columnNames;
        this.rows = rows;
    }

    @Override
    public boolean next() throws SQLException {
        if (hasNext()) {
            this.currentIndex++;
        } else {
            throw new SQLException();
        }
        return hasNext();
    }

    private boolean hasNext() {
        return currentIndex < this.rows.size();
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        if (! hasNext() || ! columnNames.contains(columnLabel)) {
            throw new SQLException();
        }
        return getColumnValue(columnLabel);
    }


    @Override
    public Object getObject(int columnIndex) throws SQLException {
        if (! hasNext() || columnIndex - 1 > this.rows.get(this.currentIndex).size()) {
            return null;
        }
        return this.rows.get(this.currentIndex).get(columnIndex - 1);
    }

    @Override
    public int findColumn(String label) throws SQLException {
        int index = -1;
        String inCase = label.toUpperCase();
        for (String colName : this.columnNames) {
            ++index;
            if (colName.toUpperCase().equals(inCase)) {
                return index;
            }
        }
        throw new SQLException();
    }

		/*
		 * Put into place to make compiling under Java 7 easier--not truly
		 * implemented
		 */
//		@Override
		public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
				return (T) getObject(columnIndex);
		}

//		@Override
		public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
				return (T) getObject(columnLabel);
		}

		private Object getColumnValue(String columnLabel) throws SQLException {
        int index = findColumn(columnLabel);
        return this.rows.get(this.currentIndex).get(index);
    }

}
