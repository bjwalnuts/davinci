/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2019 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.server.util;

import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.commons.util.DateUtils;
import edp.davinci.commons.util.MD5Utils;
import edp.davinci.commons.util.StringUtils;
import edp.davinci.core.dao.entity.Source;
import edp.davinci.core.dao.entity.User;
import edp.davinci.data.pojo.DataColumn;
import edp.davinci.data.pojo.DataResult;
import edp.davinci.data.pojo.PagingParam;
import edp.davinci.data.pojo.TableType;
import edp.davinci.data.provider.DataProviderFactory;
import edp.davinci.server.component.jdbc.JdbcDataSource;
import edp.davinci.server.enums.DatabaseTypeEnum;
import edp.davinci.server.enums.LogNameEnum;
import edp.davinci.server.enums.SqlColumnEnum;
import edp.davinci.server.enums.SqlTypeEnum;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.exception.SourceException;
import edp.davinci.server.model.CustomDataSource;
import edp.davinci.server.model.Dict;
import edp.davinci.server.model.JdbcSourceInfo;
import edp.davinci.server.model.PagingWithQueryColumns;
import edp.davinci.server.model.QueryColumn;
import edp.davinci.server.model.TableInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.druid.sql.SQLUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static edp.davinci.server.commons.Constants.*;
import static edp.davinci.server.enums.DatabaseTypeEnum.*;

@Slf4j
@Component
@Scope("prototype")
public class SqlUtils {

	private static final Logger sqlLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_SQL.getName());

    @Autowired
    private JdbcDataSource jdbcDataSource;

    @Value("${source.result-limit:1000000}")
    private int resultLimit;

    @Value("${source.enable-query-log:false}")
    private boolean isQueryLogEnable;

    private static final String TABLE = "TABLE";

    private static final String VIEW = "VIEW";

    private static final String[] TABLE_TYPES = new String[]{TABLE, VIEW};

    private static final String TABLE_NAME = "TABLE_NAME";

    private static final String TABLE_TYPE = "TABLE_TYPE";

    private JdbcSourceInfo jdbcSourceInfo;

    @Getter
    private DatabaseTypeEnum databaseTypeEnum;

    private SourceUtils sourceUtils;

    public SqlUtils init(Source source) {
    	String config = source.getConfig();
    	return SqlUtilsBuilder
                .getBuilder()
                .withUrl(SourceUtils.getUrl(config))
                .withUsername(SourceUtils.getUsername(config))
                .withPassword(SourceUtils.getPassword(config))
                .withVersion(SourceUtils.getVersion(config))
                .withProperties(SourceUtils.getProperties(config))
                .withIsExt(SourceUtils.isExt(config))
                .withJdbcDataSource(this.jdbcDataSource)
                .withResultLimit(this.resultLimit)
                .withIsQueryLogEnable(this.isQueryLogEnable)
                .withName(SourceUtils.getSourceUName(source.getProjectId(), source.getName()))
                .build();
    }

    public SqlUtils init(String jdbcUrl, String username, String password, String dbVersion, List<Dict> properties, boolean ext, String sourceName) {
        return SqlUtilsBuilder
                .getBuilder()
                .withUrl(jdbcUrl)
                .withUsername(username)
                .withPassword(password)
                .withVersion(dbVersion)
                .withProperties(properties)
                .withIsExt(ext)
                .withJdbcDataSource(this.jdbcDataSource)
                .withResultLimit(this.resultLimit)
                .withIsQueryLogEnable(this.isQueryLogEnable)
                .withName(sourceName)
                .build();
    }
    
	public static void executeByProvider(Source source, User user, String sql) throws ServerException {
		sql = SqlUtils.removeAnnotation(sql);
		SqlUtils.checkSensitiveSql(sql);
		DataProviderFactory.getProvider(source.getType()).execute(source, user, sql);
	}

	public void execute(String sql) throws ServerException {
		sql = removeAnnotation(sql);
		checkSensitiveSql(sql);
		try {
			long before = System.currentTimeMillis();
			jdbcTemplate().execute(sql);
			if (isQueryLogEnable) {
				String md5 = MD5Utils.getMD5(sql, true, 16);
				sqlLogger.info("{} query for({} ms) sql:{}", md5, System.currentTimeMillis() - before, formatSql(sql));
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			throw new ServerException(e.getMessage(), e);
		}
	}
	
	@Cacheable(value = "query", keyGenerator = "keyGenerator", sync = true)
	public PagingWithQueryColumns syncQuery4PaginateByProvider(Source source, User user, String sql, PagingParam paging,
			Set<String> excludeColumns) throws Exception {
		PagingWithQueryColumns pagingWithQueryColumns = query4PaginateByProvider(source, user, sql, paging,
				excludeColumns);
		return pagingWithQueryColumns;
	}

	@CachePut(value = "query", keyGenerator = "keyGenerator")
	public PagingWithQueryColumns query4PaginateByProvider(Source source, User user, String sql, PagingParam paging,
			Set<String> excludeColumns) throws Exception {
		sql = removeAnnotation(sql);
		checkSensitiveSql(sql);
		DataResult dataResult = DataProviderFactory.getProvider(source.getType()).getData(source, user, sql, paging);
		return toPagingWithQueryColumns(dataResult, paging, excludeColumns);
	}
    
    private PagingWithQueryColumns toPagingWithQueryColumns(DataResult dataResult, PagingParam paging, Set<String> excludeColumns) {
    	PagingWithQueryColumns pagingWithQueryColumns = new PagingWithQueryColumns();
    	pagingWithQueryColumns.setPageNo(paging.getPageNo());
    	pagingWithQueryColumns.setPageSize(paging.getPageSize());
    	pagingWithQueryColumns.setTotalCount(dataResult.getCount());
    	List<QueryColumn> columns = new ArrayList<QueryColumn>();
    	pagingWithQueryColumns.setColumns(columns);
    	List<Map<String, Object>> resultList = new ArrayList<Map<String, Object>>();
    	pagingWithQueryColumns.setResultList(resultList);
    	
    	List<List<Object>> datas = dataResult.getData();
    	List<DataColumn> headers = dataResult.getHeader();
    	for (int j = 0; j<datas.size(); j++) {

    		List<Object> row = datas.get(j);

    		Map<String, Object> rowMap = new HashMap<>();
    		for (int i = 0; i<headers.size(); i++) {
				DataColumn header = headers.get(i);
				String headerName = header.getName();
				if (excludeColumns.contains(headerName)) {
					continue;
				}
				
				if (j == 0) {
					columns.add(new QueryColumn(headerName, header.getType()));
				}
				rowMap.put(headerName, row.get(i));
			}
    		
    		resultList.add(rowMap);
		}
    	
    	return pagingWithQueryColumns;
    }
    
    @Cacheable(value = "query", keyGenerator = "keyGenerator", sync = true)
    public PagingWithQueryColumns syncQuery4Paginate(String sql, int pageNo, int pageSize, int totalCount, int limit, Set<String> excludeColumns) throws Exception {
    	PagingWithQueryColumns paginate = query4Paginate(sql, pageNo, pageSize, totalCount, limit, excludeColumns);
        return paginate;
    }
    
    @CachePut(value = "query", keyGenerator = "keyGenerator")
    public PagingWithQueryColumns query4Paginate(String sql, int pageNo, int pageSize, int totalCount, int limit, Set<String> excludeColumns) throws Exception {
    	
    	if (pageNo < 1) {
            pageNo = 0;
        }

    	if (pageSize < 1) {
            pageSize = 0;
        }

    	if (totalCount < 1) {
            totalCount = 0;
        }
    	
        PagingWithQueryColumns paginateWithQueryColumns = new PagingWithQueryColumns();
        sql = removeAnnotation(sql);
        checkSensitiveSql(sql);

        long before = System.currentTimeMillis();

        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.setMaxRows(resultLimit);

        if (pageNo < 1 && pageSize < 1) {

            if (limit > 0) {
                resultLimit = limit > resultLimit ? resultLimit : limit;
            }

            jdbcTemplate.setMaxRows(resultLimit);

            // special for mysql fetch size
			if (getDatabaseTypeEnum() == MYSQL) {
				jdbcTemplate.setFetchSize(Integer.MIN_VALUE);
			}
			
            getResultForPaginate(sql, paginateWithQueryColumns, jdbcTemplate, excludeColumns, -1);
            paginateWithQueryColumns.setPageNo(1);
            int size = paginateWithQueryColumns.getResultList().size();
            paginateWithQueryColumns.setPageSize(size);
            paginateWithQueryColumns.setTotalCount(size);

        } else {
            paginateWithQueryColumns.setPageNo(pageNo);
            paginateWithQueryColumns.setPageSize(pageSize);

            int startRow = (pageNo - 1) * pageSize;

            if (pageNo == 1 || totalCount == 0) {
                Object o = jdbcTemplate.queryForList(getCountSql(sql), Object.class).get(0);
                totalCount = Integer.parseInt(String.valueOf(o));
            }

            if (limit > 0) {
                limit = limit > resultLimit ? resultLimit : limit;
                totalCount = Math.min(limit, totalCount);
            }

            paginateWithQueryColumns.setTotalCount(totalCount);
            int maxRows = limit > 0 && limit < pageSize * pageNo ? limit : pageSize * pageNo;

            if (this.databaseTypeEnum == MYSQL) {
                sql = sql + " limit " + startRow + ", " + pageSize;
                getResultForPaginate(sql, paginateWithQueryColumns, jdbcTemplate, excludeColumns, -1);
            } else {
                jdbcTemplate.setMaxRows(maxRows);
                getResultForPaginate(sql, paginateWithQueryColumns, jdbcTemplate, excludeColumns, startRow);
            }
        }

        if (isQueryLogEnable) {
			String md5 = MD5Utils.getMD5(sql, true, 16);
			sqlLogger.info("{} query for({} ms) total count:{}, page size:{}, sql:{}", md5,
					System.currentTimeMillis() - before, paginateWithQueryColumns.getTotalCount(),
					paginateWithQueryColumns.getPageSize(), formatSql(sql));
        }

        return paginateWithQueryColumns;
    }
    
    @CachePut(value = "query", key = "#sql")
    public List<Map<String, Object>> query4List(String sql, int limit) throws Exception {
		sql = removeAnnotation(sql);
		checkSensitiveSql(sql);

		JdbcTemplate jdbcTemplate = jdbcTemplate();
		jdbcTemplate.setMaxRows(limit > resultLimit ? resultLimit : limit);

		long before = System.currentTimeMillis();
		List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
		if (isQueryLogEnable) {
			String md5 = MD5Utils.getMD5(sql, true, 16);
			sqlLogger.info("{} query for({} ms) total count:{} sql:{}", md5, System.currentTimeMillis() - before,
					list.size(), formatSql(sql));
		}

		return list;
    }

    private void getResultForPaginate(String sql, PagingWithQueryColumns paginateWithQueryColumns, JdbcTemplate jdbcTemplate, Set<String> excludeColumns, int startRow) {
		Set<String> queryFromsAndJoins = getQueryFromsAndJoins(sql);
		jdbcTemplate.query(sql, rs -> {
			if (null == rs) {
				return paginateWithQueryColumns;
			}

			ResultSetMetaData metaData = rs.getMetaData();
			List<QueryColumn> queryColumns = new ArrayList<>();
			for (int i = 1; i <= metaData.getColumnCount(); i++) {
				String key = getColumnLabel(queryFromsAndJoins, metaData.getColumnLabel(i));
				if (!CollectionUtils.isEmpty(excludeColumns) && excludeColumns.contains(key)) {
					continue;
				}
				queryColumns.add(new QueryColumn(key, metaData.getColumnTypeName(i)));
			}
			paginateWithQueryColumns.setColumns(queryColumns);

			List<Map<String, Object>> resultList = new ArrayList<>();

			try {
				if (startRow > 0) {
					rs.absolute(startRow);
				}
				while (rs.next()) {
					resultList.add(getResultObjectMap(excludeColumns, rs, metaData, queryFromsAndJoins));
				}
			} catch (Throwable e) {
				int currentRow = 0;
				while (rs.next()) {
					if (currentRow >= startRow) {
						resultList.add(getResultObjectMap(excludeColumns, rs, metaData, queryFromsAndJoins));
					}
					currentRow++;
				}
			}

			paginateWithQueryColumns.setResultList(resultList);

			return paginateWithQueryColumns;
		});
    }

    private Map<String, Object> getResultObjectMap(Set<String> excludeColumns, ResultSet rs, ResultSetMetaData metaData, Set<String> queryFromsAndJoins) throws SQLException {
		Map<String, Object> map = new LinkedHashMap<>();

		for (int i = 1; i <= metaData.getColumnCount(); i++) {
			String key = metaData.getColumnLabel(i);
			String label = getColumnLabel(queryFromsAndJoins, key);

			if (!CollectionUtils.isEmpty(excludeColumns) && excludeColumns.contains(label)) {
				continue;
			}
			map.put(label, rs.getObject(key));
		}
		return map;
    }

    public static String getCountSql(String sql) {
        String countSql = String.format(QUERY_COUNT_SQL, sql);
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            plainSelect.setOrderByElements(null);
            countSql = String.format(QUERY_COUNT_SQL, select.toString());
        } catch (JSQLParserException e) {
			log.error(e.getMessage(), e);
        }
        return SqlParseUtils.rebuildSqlWithFragment(countSql);
    }

	public static Set<String> getQueryFromsAndJoins(String sql) {
		Set<String> columnPrefixs = new HashSet<>();
		try {
			Select select = (Select) CCJSqlParserUtil.parse(sql);
			SelectBody selectBody = select.getSelectBody();
			if (selectBody instanceof PlainSelect) {
				PlainSelect plainSelect = (PlainSelect) selectBody;
				columnPrefixExtractor(columnPrefixs, plainSelect);
			}

			if (selectBody instanceof SetOperationList) {
				SetOperationList setOperationList = (SetOperationList) selectBody;
				List<SelectBody> selects = setOperationList.getSelects();
				for (SelectBody optSelectBody : selects) {
					PlainSelect plainSelect = (PlainSelect) optSelectBody;
					columnPrefixExtractor(columnPrefixs, plainSelect);
				}
			}

			if (selectBody instanceof WithItem) {
				WithItem withItem = (WithItem) selectBody;
				PlainSelect plainSelect = (PlainSelect) withItem.getSelectBody();
				columnPrefixExtractor(columnPrefixs, plainSelect);
			}
		} catch (JSQLParserException e) {
			log.error(e.getMessage(), e);
		}
		return columnPrefixs;
	}

    private static void columnPrefixExtractor(Set<String> columnPrefixs, PlainSelect plainSelect) {
        getFromItemName(columnPrefixs, plainSelect.getFromItem());
        List<Join> joins = plainSelect.getJoins();
        if (!CollectionUtils.isEmpty(joins)) {
            joins.forEach(join -> getFromItemName(columnPrefixs, join.getRightItem()));
        }
    }

    private static void getFromItemName(Set<String> columnPrefixs, FromItem fromItem) {
    	if (fromItem == null) {
    		return;
    	}

    	Alias alias = fromItem.getAlias();
        if (alias != null) {
            if (alias.isUseAs()) {
                columnPrefixs.add(alias.getName().trim() + DOT);
            } else {
                columnPrefixs.add(alias.toString().trim() + DOT);
            }
        } else {
            fromItem.accept(getFromItemTableName(columnPrefixs));
        }
    }

	public static String getColumnLabel(Set<String> columnPrefixs, String columnLable) {
		if (CollectionUtils.isEmpty(columnPrefixs)) {
			return columnLable;
		}

		for (String prefix : columnPrefixs) {
			if (columnLable.startsWith(prefix)) {
				return columnLable.replaceFirst(prefix, EMPTY);
			}
			if (columnLable.startsWith(prefix.toLowerCase())) {
				return columnLable.replaceFirst(prefix.toLowerCase(), EMPTY);
			}
			if (columnLable.startsWith(prefix.toUpperCase())) {
				return columnLable.replaceFirst(prefix.toUpperCase(), EMPTY);
			}
		}

		return columnLable;
	}

    /**
     * 获取当前连接数据库
     *
     * @return
     * @throws SourceException
     */
	public List<String> getDatabases() throws SourceException {
		List<String> dbList = new ArrayList<>();
		Connection connection = null;
		try {
			connection = sourceUtils.getConnection(this.jdbcSourceInfo);
			if (null == connection) {
				return dbList;
			}

			if (databaseTypeEnum == ORACLE) {
				dbList.add(this.jdbcSourceInfo.getUsername());
				return dbList;
			}

			if (databaseTypeEnum == ELASTICSEARCH) {
				if (StringUtils.isEmpty(this.jdbcSourceInfo.getUsername())) {
					dbList.add(databaseTypeEnum.getFeature());
				} else {
					dbList.add(this.jdbcSourceInfo.getUsername());
				}
				return dbList;
			}

			String catalog = connection.getCatalog();
			if (!StringUtils.isEmpty(catalog)) {
				dbList.add(catalog);
			} else {
				DatabaseMetaData metaData = connection.getMetaData();
				ResultSet rs = metaData.getCatalogs();
				while (rs.next()) {
					dbList.add(rs.getString(1));
				}
			}

		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return dbList;
		} finally {
			SourceUtils.releaseConnection(connection);
		}
		return dbList;
	}
    
    public List<String> getDatabaseByProvider(Source source, User user) throws SourceException { 
		return DataProviderFactory.getProvider(source.getType()).getDatabases(source, user);
	}

	/**
	 * 获取当前数据源表结构
	 *
	 * @return
	 * @throws SourceException
	 */
	public List<QueryColumn> getTableList(String dbName) throws SourceException {

		List<QueryColumn> tableList = null;
		Connection connection = null;
		ResultSet tables = null;

		try {
			connection = sourceUtils.getConnection(this.jdbcSourceInfo);
			if (null == connection) {
				return tableList;
			}

			DatabaseMetaData metaData = connection.getMetaData();
			String schema = null;
			try {
				schema = metaData.getConnection().getSchema();
			} catch (Throwable t) {
				// ignore
			}

			tables = metaData.getTables(dbName, getDBSchemaPattern(schema), "%", TABLE_TYPES);
			if (null == tables) {
				return tableList;
			}

			tableList = new ArrayList<>();
			while (tables.next()) {
				String name = tables.getString(TABLE_NAME);
				if (!StringUtils.isEmpty(name)) {
					String type = TABLE;
					try {
						type = tables.getString(TABLE_TYPE);
					} catch (Exception e) {
						// ignore
					}
					tableList.add(new QueryColumn(name, type));
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return tableList;
		} finally {
			SourceUtils.releaseConnection(connection);
			SourceUtils.closeResult(tables);
		}
		return tableList;
	}
	
	public List<QueryColumn> getTableListByProvider(Source source, User user, String dbName) throws SourceException {
		List<TableType> tables = DataProviderFactory.getProvider(source.getType()).getTables(source, user, dbName);
		List<QueryColumn> tableList = tables.stream().map( t -> {
			return new QueryColumn(t.getName(), t.getType());
		}).collect(Collectors.toList());
		return tableList;
	}

	private String getDBSchemaPattern(String schema) {
		if (databaseTypeEnum == null) {
			return null;
		}
		String schemaPattern = null;
		switch (databaseTypeEnum) {
		case ORACLE:
			schemaPattern = this.jdbcSourceInfo.getUsername();
			if (null != schemaPattern) {
				schemaPattern = schemaPattern.toUpperCase();
			}
			break;
		case SQLSERVER:
			schemaPattern = "dbo";
			break;
		case PRESTO:
			if (!StringUtils.isEmpty(schema)) {
				schemaPattern = schema;
			}
			break;
		default:
			break;
		}
		return schemaPattern;
	}

    /**
     * 获取指定表列信息
     *
     * @param tableName
     * @return
     * @throws SourceException
     */
    public TableInfo getTableInfo(String dbName, String tableName) throws SourceException {
		TableInfo tableInfo = null;
		Connection connection = null;
		try {
			connection = sourceUtils.getConnection(this.jdbcSourceInfo);
			if (null != connection) {
				DatabaseMetaData metaData = connection.getMetaData();
				List<String> primaryKeys = getPrimaryKeys(dbName, tableName, metaData);
				List<QueryColumn> columns = getColumns(dbName, tableName, metaData);
				tableInfo = new TableInfo(tableName, primaryKeys, columns);
			}
		} catch (SQLException e) {
			log.error(e.getMessage(), e);
			throw new SourceException(e.getMessage() + ", jdbcUrl=" + this.jdbcSourceInfo.getUrl());
		} finally {
			SourceUtils.releaseConnection(connection);
		}
		return tableInfo;
	}
    
    /**
     * 获取指定表列信息
     *
     * @param tableName
     * @return
     * @throws SourceException
     */
    public TableInfo getTableInfoByProvider(Source source, User user, String dbName, String tableName) throws SourceException {
		TableInfo tableInfo = new TableInfo();
		tableInfo.setTableName(tableName);
		tableInfo.setPrimaryKeys(new ArrayList<>());
		
		List<DataColumn> headers = DataProviderFactory.getProvider(source.getType()).getColumns(source, user, dbName, tableName);
		List<QueryColumn> list = headers.stream().map(h -> {
			QueryColumn column = new QueryColumn(h.getName(), h.getType());
			return column;
		}).collect(Collectors.toList());
		tableInfo.setColumns(list);
		
		return tableInfo;
    }


    /**
     * 判断表是否存在
     *
     * @param tableName
     * @return
     * @throws SourceException
     */
    public boolean tableIsExist(String tableName) throws SourceException {
        boolean result = false;
        Connection connection = null;
        ResultSet rs = null;
        try {
            connection = sourceUtils.getConnection(this.jdbcSourceInfo);
            if (null != connection) {
                rs = connection.getMetaData().getTables(null, null, tableName, null);
                if (null != rs && rs.next()) {
                    result = true;
                } else {
                    result = false;
                }
            }
        } catch (Exception e) {
			log.error(e.getMessage(), e);
            throw new SourceException("Get connection meta data error, jdbcUrl=" + this.jdbcSourceInfo.getUrl());
        } finally {
            SourceUtils.releaseConnection(connection);
            SourceUtils.closeResult(rs);
        }
        return result;
    }


    /**
     * 获取数据表主键
     *
     * @param tableName
     * @param metaData
     * @return
     * @throws ServerException
     */
    private List<String> getPrimaryKeys(String dbName, String tableName, DatabaseMetaData metaData) throws ServerException {
        ResultSet rs = null;
        List<String> primaryKeys = new ArrayList<>();
        try {
            rs = metaData.getPrimaryKeys(dbName, null, tableName);
            if (rs == null) {
				return primaryKeys;
            }
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        } catch (Exception e) {
			log.error(e.getMessage(), e);
        } finally {
            SourceUtils.closeResult(rs);
        }
        return primaryKeys;
    }


    /**
     * 获取数据表列
     *
     * @param tableName
     * @param metaData
     * @return
     * @throws ServerException
     */
    private List<QueryColumn> getColumns(String dbName, String tableName, DatabaseMetaData metaData) throws ServerException {
        ResultSet rs = null;
        List<QueryColumn> columnList = new ArrayList<>();
        try {
            if (this.databaseTypeEnum == ORACLE) {
                dbName = null;
            }
            rs = metaData.getColumns(dbName, null, tableName, "%");
            if (rs == null) {
            	return columnList;
            }
            while (rs.next()) {
                columnList.add(new QueryColumn(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")));
            }
        } catch (Exception e) {
        	log.error(e.getMessage(), e);
        } finally {
            SourceUtils.closeResult(rs);
        }
        return columnList;
    }


    /**
     * 检查敏感操作
     *
     * @param sql
     * @throws ServerException
     */
    public static void checkSensitiveSql(String sql) throws ServerException {
        Matcher matcher = PATTERN_SENSITIVE_SQL.matcher(sql.toLowerCase());
        if (matcher.find()) {
            String group = matcher.group();
            log.warn("Sensitive SQL operations are not allowed: {}", group.toUpperCase());
            throw new ServerException("Sensitive SQL operations are not allowed: " + group.toUpperCase());
        }
    }


    public JdbcTemplate jdbcTemplate() throws SourceException {
        Connection connection = null;
        try {
            connection = sourceUtils.getConnection(this.jdbcSourceInfo);
        } catch (SourceException e) {
        }
        if (connection == null) {
            sourceUtils.releaseDataSource(this.jdbcSourceInfo);
        } else {
            SourceUtils.releaseConnection(connection);
        }
        DataSource dataSource = sourceUtils.getDataSource(this.jdbcSourceInfo);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setFetchSize(500);
        return jdbcTemplate;
    }

    public boolean testConnection() throws SourceException {
		Connection connection = null;
		try {
			connection = sourceUtils.getConnection(jdbcSourceInfo);
			if (null != connection) {
				return true;
			} else {
				return false;
			}
		} catch (SourceException e) {
			throw e;
		} finally {
			SourceUtils.releaseConnection(connection);
			sourceUtils.releaseDataSource(jdbcSourceInfo);
		}
    }

    public void executeBatch(String sql, Set<QueryColumn> headers, List<Map<String, Object>> datas) throws ServerException {

        if (StringUtils.isEmpty(sql)) {
            log.info("Execute batch sql is empty");
            throw new ServerException("Execute batch sql is empty");
        }

        if (CollectionUtils.isEmpty(datas)) {
            log.info("Execute batch data is empty");
            throw new ServerException("Execute batch data is empty");
        }

        Connection connection = null;
        PreparedStatement pstmt = null;
        try {
            connection = sourceUtils.getConnection(this.jdbcSourceInfo);
            if (null != connection) {
                connection.setAutoCommit(false);
                pstmt = connection.prepareStatement(sql);
                //每1000条commit一次
                int n = 10000;
                for (Map<String, Object> map : datas) {
                    int i = 1;
                    for (QueryColumn queryColumn : headers) {
                        Object obj = map.get(queryColumn.getName());
                        switch (SqlColumnEnum.toJavaType(queryColumn.getType())) {
                            case "Short":
                                pstmt.setShort(i, null == obj || String.valueOf(obj).equals(EMPTY) ? (short) 0 : Short.parseShort(String.valueOf(obj).trim()));
                                break;
                            case "Integer":
                                pstmt.setInt(i, null == obj || String.valueOf(obj).equals(EMPTY) ? 0 : Integer.parseInt(String.valueOf(obj).trim()));
                                break;
                            case "Long":
                                pstmt.setLong(i, null == obj || String.valueOf(obj).equals(EMPTY) ? 0L : Long.parseLong(String.valueOf(obj).trim()));
                                break;
                            case "BigDecimal":
                                pstmt.setBigDecimal(i, null == obj || String.valueOf(obj).equals(EMPTY) ? null : (BigDecimal) obj);
                                break;
                            case "Float":
                                pstmt.setFloat(i, null == obj || String.valueOf(obj).equals(EMPTY) ? 0.0F : Float.parseFloat(String.valueOf(obj).trim()));
                                break;
                            case "Double":
                                pstmt.setDouble(i, null == obj || String.valueOf(obj).equals(EMPTY) ? 0.0D : Double.parseDouble(String.valueOf(obj).trim()));
                                break;
                            case "String":
                                pstmt.setString(i, (String) obj);
                                break;
                            case "Boolean":
                                pstmt.setBoolean(i, null == obj ? false : Boolean.parseBoolean(String.valueOf(obj).trim()));
                                break;
                            case "Bytes":
                                pstmt.setBytes(i, (byte[]) obj);
                                break;
                            case "Date":
                                if (obj == null) {
                                    pstmt.setDate(i, null);
                                } else {
                                    java.util.Date date = (java.util.Date) obj;
                                    pstmt.setDate(i, DateUtils.toSqlDate(date));
                                }
                                break;
                            case "DateTime":
                                if (obj == null) {
                                    pstmt.setTimestamp(i, null);
                                } else {
									if (obj instanceof LocalDateTime) {
										pstmt.setTimestamp(i, Timestamp.valueOf((LocalDateTime) obj));
									} else if (obj instanceof Date) {
										pstmt.setTimestamp(i, new Timestamp(((Date) obj).getTime()));
									} else {
										pstmt.setTimestamp(i, DateUtils.toTimestamp((DateTime) obj));
									}
                                }
                                break;
                            case "Timestamp":
								if (obj == null) {
									pstmt.setTimestamp(i, null);
								} else {
									if (obj instanceof LocalDateTime) {
										pstmt.setTimestamp(i, Timestamp.valueOf((LocalDateTime) obj));
									} else if (obj instanceof Date) {
										pstmt.setTimestamp(i, new Timestamp(((Date) obj).getTime()));
									} else {
										pstmt.setTimestamp(i, (Timestamp) obj);
									}
								}
                                break;
                            case "Blob":
                                pstmt.setBlob(i, null == obj ? null : (Blob) obj);
                                break;
                            case "Clob":
                                pstmt.setClob(i, null == obj ? null : (Clob) obj);
                                break;
                            default:
                                pstmt.setObject(i, obj);
                        }
                        i++;
                    }
                    pstmt.addBatch();
                    if (i % n == 0) {
                        try {
                            pstmt.executeBatch();
                            connection.commit();
                        } catch (BatchUpdateException e) {
                        }
                    }
                }

                pstmt.executeBatch();
                connection.commit();
            }
        } catch (Exception e) {
			log.error(e.getMessage(), e);
			if (null != connection) {
				try {
					connection.rollback();
				} catch (SQLException se) {
					log.error(se.getMessage(), se);
				}
			}
			throw new ServerException(e.getMessage(), e);
		} finally {
			if (null != pstmt) {
				try {
					pstmt.close();
				} catch (SQLException e) {
					log.error(e.getMessage(), e);
					throw new ServerException(e.getMessage(), e);
				}
			}
			SourceUtils.releaseConnection(connection);
		}
    }

    public static String getKeywordPrefix(String jdbcUrl, String dbVersion) {
        String keywordPrefix = "";
        CustomDataSource customDataSource = CustomDatabaseUtils.getInstance(jdbcUrl, dbVersion);
        if (null != customDataSource) {
            keywordPrefix = customDataSource.getKeyword_prefix();
        } else {
            DatabaseTypeEnum dataTypeEnum = DatabaseTypeEnum.urlOf(jdbcUrl);
            if (null != dataTypeEnum) {
                keywordPrefix = dataTypeEnum.getKeywordPrefix();
            }
        }
        return StringUtils.isEmpty(keywordPrefix) ? EMPTY : keywordPrefix;
    }

    public static String getKeywordSuffix(String jdbcUrl, String dbVersion) {
        String keywordSuffix = "";
        CustomDataSource customDataSource = CustomDatabaseUtils.getInstance(jdbcUrl, dbVersion);
        if (null != customDataSource) {
            keywordSuffix = customDataSource.getKeyword_suffix();
        } else {
            DatabaseTypeEnum dataTypeEnum = DatabaseTypeEnum.urlOf(jdbcUrl);
            if (null != dataTypeEnum) {
                keywordSuffix = dataTypeEnum.getKeywordSuffix();
            }
        }
        return StringUtils.isEmpty(keywordSuffix) ? EMPTY : keywordSuffix;
    }

    public static String getAliasPrefix(String jdbcUrl, String dbVersion) {
        String aliasPrefix = "";
        CustomDataSource customDataSource = CustomDatabaseUtils.getInstance(jdbcUrl, dbVersion);
        if (null != customDataSource) {
            aliasPrefix = customDataSource.getAlias_prefix();
        } else {
            DatabaseTypeEnum dataTypeEnum = DatabaseTypeEnum.urlOf(jdbcUrl);
            if (null != dataTypeEnum) {
                aliasPrefix = dataTypeEnum.getAliasPrefix();
            }
        }
        return StringUtils.isEmpty(aliasPrefix) ? EMPTY : aliasPrefix;
    }

    public static String getAliasSuffix(String jdbcUrl, String dbVersion) {
        String aliasSuffix = "";
        CustomDataSource customDataSource = CustomDatabaseUtils.getInstance(jdbcUrl, dbVersion);
        if (null != customDataSource) {
            aliasSuffix = customDataSource.getAlias_suffix();
        } else {
            DatabaseTypeEnum dataTypeEnum = DatabaseTypeEnum.urlOf(jdbcUrl);
            if (null != dataTypeEnum) {
                aliasSuffix = dataTypeEnum.getAliasSuffix();
            }
        }
        return StringUtils.isEmpty(aliasSuffix) ? EMPTY : aliasSuffix;
    }


    /**
     * 过滤sql中的注释
     *
     * @param sql
     * @return
     */
    public static String removeAnnotation(String sql) {
        sql = PATTERN_SQL_ANNOTATE.matcher(sql).replaceAll("$1");
        sql = sql.replaceAll(NEW_LINE_CHAR, SPACE).replaceAll("(;+\\s*)+", SEMICOLON);
        return sql;
    }

    public static String formatSqlType(String type) throws ServerException {
        if (!StringUtils.isEmpty(type.trim())) {
            type = type.trim().toUpperCase();
            Matcher matcher = PATTERN_DB_COLUMN_TYPE.matcher(type);
            if (!matcher.find()) {
                return SqlTypeEnum.getType(type);
            } else {
                return type;
            }
        }
        return null;
    }

    private static FromItemVisitor getFromItemTableName(Set<String> set) {
        return new FromItemVisitor() {
            @Override
            public void visit(Table tableName) {
                set.add(tableName.getName() + DOT);
            }

            @Override
            public void visit(SubSelect subSelect) {
            }

            @Override
            public void visit(SubJoin subjoin) {
            }

            @Override
            public void visit(LateralSubSelect lateralSubSelect) {
            }

            @Override
            public void visit(ValuesList valuesList) {
            }

            @Override
            public void visit(TableFunction tableFunction) {
            }

            @Override
            public void visit(ParenthesisFromItem aThis) {
            }
        };
    }

	public SqlUtils() {

	}

	public SqlUtils(JdbcSourceInfo jdbcSourceInfo) {
		this.jdbcSourceInfo = jdbcSourceInfo;
		this.databaseTypeEnum = DatabaseTypeEnum.urlOf(jdbcSourceInfo.getUrl());
	}

    public static final class SqlUtilsBuilder {
        private JdbcDataSource jdbcDataSource;
        private int resultLimit;
        private boolean isQueryLogEnable;
        private String url;
        private String username;
        private String password;
        private List<Dict> properties;
        private String version;
        private boolean isExt;
        private String name;

        private SqlUtilsBuilder() {

        }

        public static SqlUtilsBuilder getBuilder() {
            return new SqlUtilsBuilder();
        }
        
        SqlUtilsBuilder withName(String name) {
            this.name = name;
            return this;
        }

        SqlUtilsBuilder withJdbcDataSource(JdbcDataSource jdbcDataSource) {
            this.jdbcDataSource = jdbcDataSource;
            return this;
        }

        SqlUtilsBuilder withResultLimit(int resultLimit) {
            this.resultLimit = resultLimit;
            return this;
        }

        SqlUtilsBuilder withIsQueryLogEnable(boolean isQueryLogEnable) {
            this.isQueryLogEnable = isQueryLogEnable;
            return this;
        }

        SqlUtilsBuilder withUrl(String url) {
            this.url = url;
            return this;
        }

        SqlUtilsBuilder withUsername(String username) {
            this.username = username;
            return this;
        }

        SqlUtilsBuilder withPassword(String password) {
            this.password = password;
            return this;
        }

        SqlUtilsBuilder withProperties(List<Dict> properties) {
            this.properties = properties;
            return this;
        }

        SqlUtilsBuilder withVersion(String version) {
            this.version = version;
            return this;
        }

        SqlUtilsBuilder withIsExt(boolean isExt) {
            this.isExt = isExt;
            return this;
        }

        public SqlUtils build() throws ServerException {
            String database = SourceUtils.isSupportedDatabase(url);
            SourceUtils.checkDriver(database, url, version, isExt);

            JdbcSourceInfo jdbcSourceInfo = JdbcSourceInfo
                    .builder()
                    .url(this.url)
                    .username(this.username)
                    .password(this.password)
                    .database(database)
                    .version(this.version)
                    .properties(this.properties)
                    .ext(this.isExt)
                    .name(this.name)
                    .build();

            SqlUtils sqlUtils = new SqlUtils(jdbcSourceInfo);
            sqlUtils.jdbcDataSource = this.jdbcDataSource;
            sqlUtils.resultLimit = this.resultLimit;
            sqlUtils.isQueryLogEnable = this.isQueryLogEnable;
            sqlUtils.sourceUtils = new SourceUtils(this.jdbcDataSource);

            return sqlUtils;
        }
    }

    public String getUrl() {
        if (this.jdbcSourceInfo == null) {
            return null;
        }
        return this.jdbcSourceInfo.getUrl();
    }

	public static String formatSql(String sql) {
		return SQLUtils.formatMySql(sql);
	}
}

