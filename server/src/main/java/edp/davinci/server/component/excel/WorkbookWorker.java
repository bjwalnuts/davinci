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

package edp.davinci.server.component.excel;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

import edp.davinci.server.config.SpringContextHolder;
import edp.davinci.server.dao.ViewMapper;
import edp.davinci.server.dto.cronjob.MsgMailExcel;
import edp.davinci.server.dto.view.ViewExecuteParam;
import edp.davinci.server.dto.view.ViewWithProjectAndSource;
import edp.davinci.server.enums.ActionEnum;
import edp.davinci.server.enums.FileTypeEnum;
import edp.davinci.server.model.ExcelHeader;
import edp.davinci.server.service.ViewService;
import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.server.util.ExcelUtils;
import edp.davinci.server.util.FileUtils;
import edp.davinci.server.util.ScriptUtiils;
import edp.davinci.server.util.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.*;

/**
 * Created by IntelliJ IDEA.
 *
 * @Author daemon
 * @Date 19/5/29 19:29
 * To change this template use File | Settings | File Templates.
 */
@Slf4j
public class WorkbookWorker<T> extends MsgNotifier implements Callable {


    private WorkBookContext context;


    public WorkbookWorker(WorkBookContext context) {
        this.context = context;
    }


    @Override
    public T call() throws Exception {
        Stopwatch watch = Stopwatch.createStarted();
        Workbook wb = null;

        MsgWrapper wrapper = context.getWrapper();
        Object[] logArgs = {context.getTaskKey(), wrapper.getAction(), wrapper.getXId()};
        log.info("workbook worker start: taksKey={}, action={}, xid={}", logArgs);
        if (context.getCustomLogger() != null) {
        	context.getCustomLogger().info("Task({}) workbook worker start action={}, xid={}", logArgs);
        }

        String filePath = null;
        try {
            List<SheetContext> sheetContextList = buildSheetContextList();
            if (CollectionUtils.isEmpty(sheetContextList)) {
				throw new IllegalArgumentException(
						"Task(" + context.getTaskKey() + ") workbook worker sheetContextList is empty");
            }
            wb = new SXSSFWorkbook(1000);
            List<Future> futures = Lists.newArrayList();
            int sheetNo = 0;
            for (SheetContext sheetContext : sheetContextList) {
                sheetNo++;
                String name = sheetNo + "-" + sheetContext.getName();
                Sheet sheet = wb.createSheet(name);
                sheetContext.setSheet(sheet);
                sheetContext.setWorkbook(wb);
                sheetContext.setSheetNo(sheetNo);
                Future<Boolean> future = ExecutorUtil.submitSheetTask(sheetContext, context.getCustomLogger());
                futures.add(future);
            }

            Boolean rst = false;
            try {
                for (Future<Boolean> future : futures) {
                    rst = future.get(1, TimeUnit.HOURS);
                    if (!rst) {
                        future.cancel(true);
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                if (context.getCustomLogger() != null) {
                	 context.getCustomLogger().error("Task({}) workbook worker error, e={}", context.getTaskKey(), e.getMessage());
                }
                
                log.error(e.getMessage(), e);
                
            } catch (TimeoutException e) {
                if (context.getCustomLogger() != null) {
                	context.getCustomLogger().error("Task({}) workbook worker error, e={}", context.getTaskKey(), e.getMessage());
                }
                if (wrapper.getAction() == ActionEnum.MAIL) {
                    MsgMailExcel msg = (MsgMailExcel) wrapper.getMsg();
                    msg.setException(new TimeoutException("Get data timeout"));
                    super.tell(wrapper);
                }
                return (T) filePath;
            }

            if (rst) {
                filePath = ((FileUtils) SpringContextHolder.getBean(FileUtils.class)).getFilePath(FileTypeEnum.XLSX, this.context.getWrapper());
                try (FileOutputStream out = new FileOutputStream(filePath);) {
                    wb.write(out);
                    out.flush();
                } catch (Exception e) {
                    filePath = null;
                    throw e;
                }
                wrapper.setRst(filePath);
            } else {
            	log.info("Task({}) workbook worker fail, xid={}, xUUID={}", context.getTaskKey(), wrapper.getXId(), wrapper.getXUUID());
                wrapper.setRst(null);
            }
            super.tell(wrapper);
        } catch (Exception e) {
            if (context.getCustomLogger() != null) {
            	context.getCustomLogger().error("Task({}) workbook worker error, e={}", context.getTaskKey(), e.getMessage());
            }
            if (wrapper.getAction() == ActionEnum.MAIL) {
                MsgMailExcel msg = (MsgMailExcel) wrapper.getMsg();
                msg.setException(e);
            }

            log.error(e.getMessage(), e);

            super.tell(wrapper);
            if (StringUtils.isNotEmpty(filePath)) {
                FileUtils.delete(filePath);
            }
        } finally {
            wb = null;
        }

        Object[] args = { context.getTaskKey(), StringUtils.isNotEmpty(filePath), wrapper.getAction(), wrapper.getXId(),
				wrapper.getXUUID(), filePath, watch.elapsed(TimeUnit.MILLISECONDS) };
		if (context.getCustomLogger() != null) {
			context.getCustomLogger().info(
					"Task({}) workbook worker complete status={}, action={}, xid={}, xUUID={}, filePath={}, cost={}ms",
					args);
		}

        return (T) filePath;
    }


    private List<SheetContext> buildSheetContextList() throws Exception {
        List<SheetContext> sheetContextList = Lists.newArrayList();
        for (WidgetContext context : context.getWidgets()) {
            ViewExecuteParam executeParam = null;
            if (context.isHasExecuteParam() && null != context.getExecuteParam()) {
                executeParam = context.getExecuteParam();
            } else {
                executeParam = ScriptUtiils.getViewExecuteParam(ScriptUtiils.getExecuptParamScriptEngine(),
                        context.getDashboard() != null ? context.getDashboard().getConfig() : null,
                        context.getWidget().getConfig(),
                        context.getMemDashboardWidget() != null ? context.getMemDashboardWidget().getId() : null);
            }

            ViewWithProjectAndSource viewWithProjectAndSource = ((ViewMapper) SpringContextHolder.getBean(ViewMapper.class)).getViewWithProjectAndSourceById(context.getWidget().getViewId());

            SQLContext sqlContext = ((ViewService) SpringContextHolder.getBean(ViewService.class)).getSQLContext(context.getIsMaintainer(), viewWithProjectAndSource, executeParam, this.context.getUser());

            SqlUtils sqlUtils = ((SqlUtils) SpringContextHolder.getBean(SqlUtils.class)).init(viewWithProjectAndSource.getSource());

            boolean isTable;
            List<ExcelHeader> excelHeaders = null;
            if (isTable = ExcelUtils.isTable(context.getWidget().getConfig())) {
                excelHeaders = ScriptUtiils.formatHeader(ScriptUtiils.getCellValueScriptEngine(), context.getWidget().getConfig(),
                        sqlContext.getViewExecuteParam().getParams());
            }
            SheetContext sheetContext = SheetContext.SheetContextBuilder.newBuilder()
                    .withExecuteSql(sqlContext.getExecuteSql())
                    .withQuerySql(sqlContext.getQuerySql())
                    .withExcludeColumns(sqlContext.getExcludeColumns())
                    .withContain(Boolean.FALSE)
                    .withSqlUtils(sqlUtils)
                    .withIsTable(isTable)
                    .withExcelHeaders(excelHeaders)
                    .withDashboardId(null != context.getDashboard() ? context.getDashboard().getId() : null)
                    .withWidgetId(context.getWidget().getId())
                    .withName(context.getWidget().getName())
                    .withWrapper(this.context.getWrapper())
                    .withResultLimit(this.context.getResultLimit())
                    .withTaskKey(this.context.getTaskKey())
                    .withCustomLogger(this.context.getCustomLogger())
                    .build();
            sheetContextList.add(sheetContext);
        }
        return sheetContextList;
    }
}
