package com.akto.testing.workflow_node_executor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import com.akto.dao.context.Context;
import com.akto.dao.test_editor.TestEditorEnums;
import com.akto.dao.test_editor.YamlTemplateDao;
import com.akto.dto.ApiInfo;
import com.akto.dto.CustomAuthType;
import com.akto.dto.OriginalHttpResponse;
import com.akto.dto.RawApi;
import com.akto.dto.api_workflow.Node;
import com.akto.dto.test_editor.ConfigParserResult;
import com.akto.dto.test_editor.ExecuteAlgoObj;
import com.akto.dto.test_editor.ExecutionOrderResp;
import com.akto.dto.test_editor.ExecutionResult;
import com.akto.dto.test_editor.ExecutorNode;
import com.akto.dto.test_editor.ExecutorSingleRequest;
import com.akto.dto.test_editor.FilterNode;
import com.akto.dto.test_editor.TestConfig;
import com.akto.dto.testing.AuthMechanism;
import com.akto.dto.testing.GenericTestResult;
import com.akto.dto.testing.TestResult;
import com.akto.dto.testing.TestingRunConfig;
import com.akto.dto.testing.TestingRunResult;
import com.akto.dto.testing.WorkflowTestResult;
import com.akto.dto.testing.YamlNodeDetails;
import com.akto.dto.testing.WorkflowTestResult.NodeResult;
import com.akto.store.SampleMessageStore;
import com.akto.store.TestingUtil;
import com.akto.test_editor.execution.ExecutionListBuilder;
import com.akto.test_editor.execution.Executor;
import com.akto.test_editor.execution.ExecutorAlgorithm;
import com.akto.testing.ApiExecutor;
import com.akto.testing.TestExecutor;
import com.akto.utils.RedactSampleData;
import com.google.gson.Gson;

public class YamlNodeExecutor extends NodeExecutor {
    
    private static final Gson gson = new Gson();

    public NodeResult processNode(Node node, Map<String, Object> varMap, Boolean allowAllStatusCodes) {
        List<String> testErrors = new ArrayList<>();

        YamlNodeDetails yamlNodeDetails = (YamlNodeDetails) node.getWorkflowNodeDetails();

        if (yamlNodeDetails.getTestId() != null && yamlNodeDetails.getTestId().length() > 0) {
            return processYamlNode(node, varMap, allowAllStatusCodes, yamlNodeDetails);
        }
        
        RawApi rawApi = yamlNodeDetails.getRawApi();
        RawApi sampleRawApi = rawApi.copy();
        List<RawApi> rawApis = new ArrayList<>();
        rawApis.add(rawApi.copy());

        Executor executor = new Executor();
        ExecutorNode executorNode = yamlNodeDetails.getExecutorNode();
        FilterNode validatorNode = yamlNodeDetails.getValidatorNode();

        for (ExecutorNode execNode: executorNode.getChildNodes()) {
            if (execNode.getNodeType().equalsIgnoreCase(TestEditorEnums.ValidateExecutorDataOperands.Validate.toString())) {
                validatorNode = (FilterNode) execNode.getChildNodes().get(0).getValues();
            }
        }

        AuthMechanism authMechanism = yamlNodeDetails.getAuthMechanism();
        List<CustomAuthType> customAuthTypes = yamlNodeDetails.getCustomAuthTypes();

        ExecutionListBuilder executionListBuilder = new ExecutionListBuilder();
        List<ExecutorNode> executorNodes = new ArrayList<>();
        boolean followRedirect = executionListBuilder.buildExecuteOrder(executorNode, executorNodes);

        ExecutorAlgorithm executorAlgorithm = new ExecutorAlgorithm(sampleRawApi, varMap, authMechanism, customAuthTypes);
        Map<Integer, ExecuteAlgoObj> algoMap = new HashMap<>();
        ExecutorSingleRequest singleReq = executorAlgorithm.execute(executorNodes, 0, algoMap, rawApis, false, 0);

        //ExecutorSingleRequest singleReq = executor.buildTestRequest(executorNode, null, rawApis, varMap, authMechanism, customAuthTypes);
        //List<RawApi> testRawApis = singleReq.getRawApis();
        TestingRunConfig testingRunConfig = new TestingRunConfig();
        String logId = "";
        List<TestResult> result = new ArrayList<>();
        boolean vulnerable = false;

        OriginalHttpResponse testResponse;
        List<String> message = new ArrayList<>();
        //String message = null;
        String savedResponses = null;
        int statusCode = 0;
        List<Integer> responseTimeArr = new ArrayList<>();
        List<Integer> responseLenArr = new ArrayList<>();

        for (RawApi testReq: rawApis) {
            if (vulnerable) {
                break;
            }
            if (testReq.equals(sampleRawApi)) {
                continue;
            }
            int tsBeforeReq = 0;
            int tsAfterReq = 0;
            try {
                tsBeforeReq = Context.nowInMillis();
                testResponse = ApiExecutor.sendRequest(testReq.getRequest(), followRedirect, testingRunConfig);                
                tsAfterReq = Context.nowInMillis();
                responseTimeArr.add(tsAfterReq - tsBeforeReq);
                ExecutionResult attempt = new ExecutionResult(singleReq.getSuccess(), singleReq.getErrMsg(), testReq.getRequest(), testResponse);
                TestResult res = executor.validate(attempt, sampleRawApi, varMap, logId, validatorNode, yamlNodeDetails.getApiInfoKey());
                if (res != null) {
                    result.add(res);
                }
                vulnerable = res.getVulnerable();
                try {
                    message.add(RedactSampleData.convertOriginalReqRespToString(testReq.getRequest(), testResponse));
                } catch (Exception e) {
                    ;
                }

                // save response in a list
                savedResponses = testResponse.getBody();
                statusCode = testResponse.getStatusCode();

                if (testResponse.getBody() == null) {
                    responseLenArr.add(0);
                } else {
                    responseLenArr.add(testResponse.getBody().length());
                }

            } catch (Exception e) {
                // TODO: handle exception
            }
        }

        calcTimeAndLenStats(node.getId(), responseTimeArr, responseLenArr, varMap);

        if (savedResponses != null) {
            varMap.put(node.getId() + ".response.body", savedResponses);
            varMap.put(node.getId() + ".response.status_code", String.valueOf(statusCode));
        }

        // call executor's build request, which returns all rawapi by taking a rawapi argument
        // valuemap -> use varmap

        // loop on all rawapis and hit requests
        // call validate node present in node
        // if vulnerable, populate map

        // 

        return new WorkflowTestResult.NodeResult(message.toString(), vulnerable, testErrors);

    }

    public void calcTimeAndLenStats(String nodeId, List<Integer> responseTimeArr, List<Integer> responseLenArr, Map<String, Object> varMap) {

        Map<String, Double> m = new HashMap<>();
        m = calcStats(responseTimeArr);
        for (String k: m.keySet()) {
            if (k.equals("last")) {
                varMap.put(nodeId + ".response.response_time", m.getOrDefault(k, 0.0));
            } else {
                varMap.put(nodeId + ".response.stats." + k + "_response_time", m.getOrDefault(k, 0.0));
            }
        }

        m = calcStats(responseLenArr);
        for (String k: m.keySet()) {
            if (k.equals("last")) {
                varMap.put(nodeId + ".response.response_len", m.getOrDefault(k, 0.0));
            } else {
                varMap.put(nodeId + ".response.stats." + k + "_response_len", m.getOrDefault(k, 0.0));
            }
        }

    }

    public Map<String, Double> calcStats(List<Integer> arr) {
        Map<String, Double> m = new HashMap<>();
        if (arr.size() == 0) {
            return m;
        }
        double last = 0.0;
        double median;
        last = arr.get(arr.size() - 1);
        Collections.sort(arr);

        int total = 0;
        for (int i = 0; i < arr.size(); i++) {
            total += arr.get(i);
        }
        
        if (arr.size() % 2 == 0)
            median = ((double)arr.get(arr.size()/2) + (double)arr.get(arr.size()/2 - 1))/2;
        else
            median = (double) arr.get(arr.size()/2);
        
        m.put("min", (double) arr.get(0));
        m.put("max", (double) arr.get(arr.size() - 1));
        m.put("average", (double) (total/arr.size()));
        m.put("median", median);
        m.put("last", last);

        return m;
    }

    public WorkflowTestResult.NodeResult processYamlNode(Node node, Map<String, Object> valuesMap, Boolean allowAllStatusCodes, YamlNodeDetails yamlNodeDetails) {

        String testSubCategory = yamlNodeDetails.getTestId();
        Map<String, TestConfig> testConfigMap = YamlTemplateDao.instance.fetchTestConfigMap(false, false);
        TestConfig testConfig = testConfigMap.get(testSubCategory);

        ExecutorNode executorNode = yamlNodeDetails.getExecutorNode();

        for (ExecutorNode execNode: executorNode.getChildNodes()) {
            if (execNode.getNodeType().equalsIgnoreCase(TestEditorEnums.ValidateExecutorDataOperands.Validate.toString())) {
                FilterNode validatorNode = (FilterNode) execNode.getChildNodes().get(0).getValues();
                ConfigParserResult configParserResult = testConfig.getValidation();
                configParserResult.setNode(validatorNode);
                testConfig.setValidation(configParserResult);
            }
        }

        RawApi rawApi = yamlNodeDetails.getRawApi();

        Map<String, Object> m = new HashMap<>();
        for (String k: rawApi.getRequest().getHeaders().keySet()) {
            List<String> v = rawApi.getRequest().getHeaders().getOrDefault(k, new ArrayList<>());
            if (v.size() > 0) {
                m.put(k, v.get(0).toString());
            }
        }

        Map<String, Object> m2 = new HashMap<>();
        for (String k: rawApi.getResponse().getHeaders().keySet()) {
            List<String> v = rawApi.getRequest().getHeaders().getOrDefault(k, new ArrayList<>());
            if (v.size() > 0) {
                m2.put(k, v.get(0).toString());
            }
        }

        JSONObject json = new JSONObject() ;
        json.put("method", rawApi.getRequest().getMethod());
        json.put("requestPayload", rawApi.getRequest().getBody());
        json.put("path", rawApi.getRequest().getUrl());
        json.put("requestHeaders", gson.toJson(m));
        json.put("type", "");
        json.put("responsePayload", rawApi.getResponse().getBody());
        json.put("responseHeaders", gson.toJson(m2));
        json.put("statusCode", Integer.toString(rawApi.getResponse().getStatusCode()));
        
        AuthMechanism authMechanism = yamlNodeDetails.getAuthMechanism();
        Map<ApiInfo.ApiInfoKey, List<String>> sampleDataMap = new HashMap<>();
        sampleDataMap.put(yamlNodeDetails.getApiInfoKey(), Collections.singletonList(json.toString()));
        SampleMessageStore messageStore = SampleMessageStore.create(sampleDataMap);
        List<CustomAuthType> customAuthTypes = yamlNodeDetails.getCustomAuthTypes();
        TestingUtil testingUtil = new TestingUtil(authMechanism, messageStore, null, null, customAuthTypes);
        TestExecutor executor = new TestExecutor();
        TestingRunResult testingRunResult = executor.runTestNew(yamlNodeDetails.getApiInfoKey(), null, testingUtil, null, testConfig, null);

        List<String> errors = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        if (testingRunResult.isVulnerable()) {
            List<GenericTestResult> testResults = testingRunResult.getTestResults();
            for (GenericTestResult testResult: testResults) {
                TestResult t = (TestResult) testResult;
                messages.add(t.getMessage());
            }
        }

        return new WorkflowTestResult.NodeResult(messages.toString(), testingRunResult.isVulnerable(), errors);
    }
    
}
