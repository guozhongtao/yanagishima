package yanagishima.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.fluent.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yanagishima.config.YanagishimaConfig;
import yanagishima.result.PrestoQueryResult;
import yanagishima.service.PrestoService;
import yanagishima.util.AccessControlUtil;
import yanagishima.util.HttpRequestUtil;
import yanagishima.util.JsonUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static yanagishima.util.Constants.YANAGISHIMA_COMMENT;

@Singleton
public class PrestoPartitionServlet extends HttpServlet {

    private static Logger LOGGER = LoggerFactory
            .getLogger(PrestoPartitionServlet.class);

    private static final long serialVersionUID = 1L;

    private PrestoService prestoService;

    private YanagishimaConfig yanagishimaConfig;

    @Inject
    public PrestoPartitionServlet(PrestoService prestoService, YanagishimaConfig yanagishimaConfig) {
        this.prestoService = prestoService;
        this.yanagishimaConfig = yanagishimaConfig;
    }

    @Override
    protected void doPost(HttpServletRequest request,
                         HttpServletResponse response) throws ServletException, IOException {

        HashMap<String, Object> retVal = new HashMap<String, Object>();

        try {

            String datasource = HttpRequestUtil.getParam(request, "datasource");
            if (yanagishimaConfig.isCheckDatasource()) {
                if (!AccessControlUtil.validateDatasource(request, datasource)) {
                    try {
                        response.sendError(SC_FORBIDDEN);
                        return;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            String userName = null;
            Optional<String> prestoUser = Optional.ofNullable(request.getParameter("user"));
            Optional<String> prestoPassword = Optional.ofNullable(request.getParameter("password"));
            if(yanagishimaConfig.isUseAuditHttpHeaderName()) {
                userName = request.getHeader(yanagishimaConfig.getAuditHttpHeaderName());
            } else {
                if (prestoUser.isPresent() && prestoPassword.isPresent()) {
                    userName = prestoUser.get();
                }
            }
            String catalog = HttpRequestUtil.getParam(request, "catalog");
            String schema = HttpRequestUtil.getParam(request, "schema");
            String table = HttpRequestUtil.getParam(request, "table");
            String partitionColumn = request.getParameter("partitionColumn");
            String partitionColumnType = request.getParameter("partitionColumnType");
            String partitionValue = request.getParameter("partitionValue");
            if (partitionColumn == null || partitionValue == null) {
                Optional<String> webhdfsUrlOptional = yanagishimaConfig.getWebhdfsUrl(datasource, catalog, schema, table);
                if(webhdfsUrlOptional.isPresent()) {
                    String webhdfsUrl = webhdfsUrlOptional.get();
                    String json = Request.Get(webhdfsUrl).execute().returnContent().asString(UTF_8);
//                    {
//                        "FileStatuses": {
//                        "FileStatus": [
//                        {
//                                "accessTime": 0,
//                                "blockSize": 0,
//                                "childrenNum": 271,
//                                "fileId": 43732679,
//                                "group": "hdfs",
//                                "length": 0,
//                                "modificationTime": 1527567948631,
//                                "owner": "hive",
//                                "pathSuffix": "hoge=piyo",
//                                "permission": "777",
//                                "replication": 0,
//                                "storagePolicy": 0,
//                                "type": "DIRECTORY"
//                        },
                    ObjectMapper mapper = new ObjectMapper();
                    Map map = mapper.readValue(json, Map.class);
                    List<Map> topLevelPartitionList = (List) ((Map) map.get("FileStatuses")).get("FileStatus");
                    if(topLevelPartitionList.size() > 0) {
                        String firstPartitionData = (String)topLevelPartitionList.get(0).get("pathSuffix");
                        if(firstPartitionData != null) {
                            retVal.put("column", firstPartitionData.split("=")[0]);
                            Set<String> partitions = new TreeSet<>();
                            for(Map m : topLevelPartitionList) {
                                String data = (String)m.get("pathSuffix");
                                if(data != null) {
                                    partitions.add(data.split("=")[1]);
                                }
                            }
                            retVal.put("partitions", partitions);
                        }
                    }
                } else {
                    String query = null;
                    if(yanagishimaConfig.isUseNewShowPartitions(datasource)) {
                        query = String.format("%sSELECT * FROM  %s.%s.\"%s$partitions\"", YANAGISHIMA_COMMENT, catalog, schema, table);
                    } else {
                        query = String.format("%sSHOW PARTITIONS FROM %s.%s.\"%s\"", YANAGISHIMA_COMMENT, catalog, schema, table);
                    }
                    if(userName != null) {
                        LOGGER.info(String.format("%s executed %s in %s", userName, query, datasource));
                    }
                    PrestoQueryResult prestoQueryResult = prestoService.doQuery(datasource, query, userName, prestoUser, prestoPassword, false, Integer.MAX_VALUE);
                    retVal.put("column", prestoQueryResult.getColumns().get(0));
                    Set<String> partitions = new TreeSet<>();
                    List<List<String>> records = prestoQueryResult.getRecords();
                    for (List<String> row : records) {
                        String data = row.get(0);
                        if(data != null) {
                            partitions.add(data);
                        }
                    }
                    retVal.put("partitions", partitions);
                }
            } else {
                String[] partitionColumnArray = partitionColumn.split(",");
                String[] partitionColumnTypeArray = partitionColumnType.split(",");
                String[] partitionValuesArray = partitionValue.split(",");
                if(partitionColumnArray.length != partitionValuesArray.length) {
                    throw new RuntimeException("The number of partitionColumn must be same as partitionValue");
                }
                List whereList = new ArrayList<>();
                for(int i=0; i<partitionColumnArray.length; i++) {
                    if(partitionColumnTypeArray[i].equals("varchar")) {
                        whereList.add(String.format("%s = '%s'", partitionColumnArray[i], partitionValuesArray[i]));
                    } else {
                        whereList.add(String.format("%s = %s", partitionColumnArray[i], partitionValuesArray[i]));
                    }
                }
                String query = null;
                if(yanagishimaConfig.isUseNewShowPartitions(datasource)) {
                    query = String.format("%sSELECT * FROM  %s.%s.\"%s$partitions\" WHERE %s", YANAGISHIMA_COMMENT, catalog, schema, table, String.join(" AND ", whereList));
                } else {
                    query = String.format("%sSHOW PARTITIONS FROM %s.%s.%s WHERE %s", YANAGISHIMA_COMMENT, catalog, schema, table, String.join(" AND ", whereList));
                }
                if(userName != null) {
                    LOGGER.info(String.format("%s executed %s in %s", userName, query, datasource));
                }
                PrestoQueryResult prestoQueryResult = prestoService.doQuery(datasource, query, userName, prestoUser, prestoPassword, false, Integer.MAX_VALUE);
                List<String> columns = prestoQueryResult.getColumns();
                int index = 0;
                for (String column : columns) {
                    if (column.equals(partitionColumnArray[partitionColumnArray.length-1])) {
                        break;
                    }
                    index++;
                }
                retVal.put("column", columns.get(index + 1));
                Set<String> partitions = new TreeSet<>();
                List<List<String>> records = prestoQueryResult.getRecords();
                for (List<String> row : records) {
                    partitions.add(row.get(index + 1));
                }
                retVal.put("partitions", partitions);
            }


        } catch (Throwable e) {
            LOGGER.error(e.getMessage(), e);
            retVal.put("error", e.getMessage());
        }

        JsonUtil.writeJSON(response, retVal);

    }

}
