package com.akto.dto.test_editor.api_filters;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.akto.dto.ApiInfo;
import com.akto.dto.OriginalHttpRequest;
import com.akto.dto.OriginalHttpResponse;
import com.akto.dto.RawApi;
import com.akto.dto.test_editor.ApiSelectionFilters;
import com.akto.dto.test_editor.ContainsParam;
import com.akto.dto.test_editor.TestConfig;
import com.akto.dto.test_editor.Utils;
import com.akto.util.HttpRequestResponseUtils;

public class ContainsParamFilter extends ApiFilter {
   
    @Override
    public boolean isValid(TestConfig testConfig, RawApi rawApi, ApiInfo.ApiInfoKey apiInfoKey) {

        OriginalHttpRequest originalHttpRequest = rawApi.getRequest().copy();
        OriginalHttpResponse originalHttpResponse = rawApi.getResponse().copy();
        ApiSelectionFilters filters = testConfig.getApiSelectionFilters();
        if (filters == null) {
            return true;
        }
        List<ContainsParam> containsParamFilter = filters.getContainsParams();
        Boolean found = false;
        if (containsParamFilter == null || containsParamFilter.size() == 0) {
            return true;
        }

        for (ContainsParam containParam: containsParamFilter) {

            found = false;

            String param = containParam.getParam();
            List<String> paramRegex = containParam.getParamRegex();
            List<String> searchIn = containParam.getSearchIn();

            for (String location: searchIn) {
                found = false;
                for (String regex : paramRegex) {
                    if (location.equals("queryParam")) {
                        found = isPresentInQueryParams(originalHttpRequest, regex);
                    } else if (location.equals("request-body")) {
                        found = isPresentInRequest(originalHttpRequest, regex);
                    } else if (location.equals("response-body")) {
                        found = isPresentInResponse(originalHttpResponse, regex);
                    } else if (location.equals("request-header")) {
                        found = isPresentInRequestHeader(originalHttpRequest, regex);
                    }
                    if (found) {
                        break;
                    }
                }
                if (found) {
                    break;
                }   
            }

            if (!found) {
                return false;
            }

        }
        return found;
    }

    public Boolean isPresentInQueryParams(OriginalHttpRequest request, String paramRegex) {

        String queryParamString = HttpRequestResponseUtils.convertFormUrlEncodedToJson(request.getQueryParams());
        if (queryParamString == null) {
            return false;
        }
        return Utils.checkIfContainsMatch(queryParamString, paramRegex);
    }

    public Boolean isPresentInRequest(OriginalHttpRequest request, String paramRegex) {

        if (request.getBody() == null) {
            return false;
        }
        return Utils.checkIfContainsMatch(request.getBody(), paramRegex);
    }

    public Boolean isPresentInRequestHeader(OriginalHttpRequest request, String paramRegex) {

        if (request.getHeaders() == null) {
            return false;
        }

        Set<String> headerValSet = new HashSet<>();
        for (String headerName: request.getHeaders().keySet()) {
            List<String> val = request.getHeaders().get(headerName);
            headerValSet.addAll(val);
        }

        for (String headerVal: headerValSet) {
            if (Utils.checkIfContainsMatch(headerVal, paramRegex)) {
                return true;
            }
        }

        return false;
    }

    public Boolean isPresentInResponse(OriginalHttpResponse response, String paramRegex) {
        if (response.getBody() == null) {
            return false;
        }
        return Utils.checkIfContainsMatch(response.getBody(), paramRegex);
    }

}
