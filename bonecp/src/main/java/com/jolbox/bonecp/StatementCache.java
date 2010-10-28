/**
 *  Copyright 2010 Wallace Wadge
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jolbox.bonecp;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.MapMaker;


/**
 * JDBC statement cache.
 *
 * @author wallacew
 */
public class StatementCache implements IStatementCache {
	/** Logger class. */
	private static Logger logger = LoggerFactory.getLogger(StatementCache.class);
	/** The cache of our statements. */
	private ConcurrentMap<String, StatementHandle> cache;
	/** How many items to cache. */
	private int cacheSize;
	/** If true, keep statistics. */
	private final boolean maintainStats;
	
	/**
	 * Creates a statement cache of given size. 
	 *
	 * @param size of cache.
	 * @param maintainStats if true, keep track of statistics.
	 */
	public StatementCache(int size, boolean maintainStats){
		this.maintainStats = maintainStats;
		this.cache = new MapMaker()
		.concurrencyLevel(32)
		.makeMap();

		this.cacheSize = size;
	}

	/** Simply appends the given parameters and returns it to obtain a cache key
	 * @param sql
	 * @param resultSetConcurrency
	 * @param resultSetHoldability
	 * @param resultSetType
	 * @return cache key to use
	 */
	public String calculateCacheKey(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability){
		StringBuilder tmp = calculateCacheKeyInternal(sql, resultSetType,
				resultSetConcurrency);

		tmp.append(", H:");
		tmp.append(resultSetHoldability);

		return tmp.toString();
	}

	/** Cache key calculation.
	 * @param sql string
	 * @param resultSetType
	 * @param resultSetConcurrency
	 * @return cache key
	 */
	public String calculateCacheKey(String sql, int resultSetType, int resultSetConcurrency){
		StringBuilder tmp = calculateCacheKeyInternal(sql, resultSetType,
				resultSetConcurrency);

		return tmp.toString();
	}

	/** Cache key calculation.
	 * @param sql
	 * @param resultSetType
	 * @param resultSetConcurrency
	 * @return cache key
	 */
	private StringBuilder calculateCacheKeyInternal(String sql,
			int resultSetType, int resultSetConcurrency) {
		StringBuilder tmp = new StringBuilder(sql.length()+20);
		tmp.append(sql);

		tmp.append(", T");
		tmp.append(resultSetType);
		tmp.append(", C");
		tmp.append(resultSetConcurrency);
		return tmp;
	}


	/** Alternate version of autoGeneratedKeys.
	 * @param sql
	 * @param autoGeneratedKeys
	 * @return cache key to use.
	 */
	public String calculateCacheKey(String sql, int autoGeneratedKeys) {
		StringBuilder tmp = new StringBuilder(sql.length()+4);
		tmp.append(sql);
		tmp.append(autoGeneratedKeys);
		return tmp.toString();
	}

	/** Calculate a cache key.
	 * @param sql to use
	 * @param columnIndexes to use
	 * @return cache key to use.
	 */
	public String calculateCacheKey(String sql, int[] columnIndexes) {
		StringBuilder tmp = new StringBuilder(sql.length()+4);
		tmp.append(sql);
		for (int i=0; i < columnIndexes.length; i++){
			tmp.append(columnIndexes[i]);
			tmp.append("CI,");
		}
		return tmp.toString();
	}

	/** Calculate a cache key.
	 * @param sql to use
	 * @param columnNames to use
	 * @return cache key to use.
	 */
	public String calculateCacheKey(String sql, String[] columnNames) {
		StringBuilder tmp = new StringBuilder(sql.length()+4);
		tmp.append(sql);
		for (int i=0; i < columnNames.length; i++){
			tmp.append(columnNames[i]);
			tmp.append("CN,");
		}
		return tmp.toString();

	}

	/**
	 * {@inheritDoc}
	 *
	 * @see com.jolbox.bonecp.IStatementCache#get(java.lang.String)
	 */
//	@Override
	public StatementHandle get(String key){
		StatementHandle statement = this.cache.get(key);
		
		if (statement != null && !statement.logicallyClosed.compareAndSet(true, false)){
			statement = null;
		}
		
		if (this.maintainStats){
			if (statement != null){
				Statistics.cacheHits.incrementAndGet();
			} else {
				Statistics.cacheMiss.incrementAndGet();
			}
		}
		return statement;
	}

	// @Override
	public StatementHandle get(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
		return get(calculateCacheKey(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
	}


	// @Override
	public StatementHandle get(String sql, int resultSetType, int resultSetConcurrency) {
		return get(calculateCacheKey(sql, resultSetType, resultSetConcurrency));
	}

	// @Override
	public StatementHandle get(String sql, int autoGeneratedKeys) {
		return get(calculateCacheKey(sql, autoGeneratedKeys));
	}


	// @Override
	public StatementHandle get(String sql, int[] columnIndexes) {
		return get(calculateCacheKey(sql, columnIndexes));
	}


	// @Override
	public StatementHandle get(String sql, String[] columnNames) {
		return get(calculateCacheKey(sql, columnNames));
	}



	/**
	 * {@inheritDoc}
	 *
	 * @see com.jolbox.bonecp.IStatementCache#put(java.lang.String, com.jolbox.bonecp.StatementHandle)
	 */
	// @Override
	public void put(String key, StatementHandle handle){
		
		if (this.cache.size() <=  this.cacheSize && key != null){ // perhaps use LRU in future?? Worth the overhead? Hmm....
			if (this.cache.putIfAbsent(key, handle) == null){
				handle.inCache = true;
				if (this.maintainStats){
					Statistics.statementsCached.incrementAndGet();
				}
			}
		}
		
	}


	/**
	 * {@inheritDoc}
	 *
	 * @see com.jolbox.bonecp.IStatementCache#size()
	 */
	// @Override
	public int size(){
		return this.cache.size();
	}



	/**
	 * {@inheritDoc}
	 *
	 * @see com.jolbox.bonecp.IStatementCache#clear()
	 */
	// @Override
	public void clear() {
		for (StatementHandle statement: this.cache.values()){
			try {
				statement.internalClose();
			} catch (SQLException e) {
				// don't log, we might fail if the connection link has died
				// logger.error("Error closing off statement", e);
			}
		}
		this.cache.clear();
	}

	// @Override
	public void checkForProperClosure() {
		for (StatementHandle statement: this.cache.values()){
			if (!statement.isClosed()){
				logger.error("Statement not closed properly in application\n\n"+statement.getOpenStackTrace());
			}
		}		
	}

}
