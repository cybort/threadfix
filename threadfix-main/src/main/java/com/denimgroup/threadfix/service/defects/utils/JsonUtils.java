////////////////////////////////////////////////////////////////////////
//
//     Copyright (c) 2009-2015 Denim Group, Ltd.
//
//     The contents of this file are subject to the Mozilla Public License
//     Version 2.0 (the "License"); you may not use this file except in
//     compliance with the License. You may obtain a copy of the License at
//     http://www.mozilla.org/MPL/
//
//     Software distributed under the License is distributed on an "AS IS"
//     basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//     License for the specific language governing rights and limitations
//     under the License.
//
//     The Original Code is ThreadFix.
//
//     The Initial Developer of the Original Code is Denim Group, Ltd.
//     Portions created by Denim Group, Ltd. are Copyright (C)
//     Denim Group, Ltd. All Rights Reserved.
//
//     Contributor(s): Denim Group, Ltd.
//
////////////////////////////////////////////////////////////////////////

package com.denimgroup.threadfix.service.defects.utils;

import com.denimgroup.threadfix.logging.SanitizedLogger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.Nullable;

/**
 * Created by mac on 4/4/14.
 */
public class JsonUtils {

    private static final SanitizedLogger LOG = new SanitizedLogger(JsonUtils.class);

    /**
     * Convenience method to wrap the exception catching.
     * TODO validate to save generating an exception on invalid input
     * @param responseContents
     * @return JSON array object or null
     */
    @Nullable
    public static JSONArray getJSONArray(String responseContents) {
        try {
            return new JSONArray(responseContents);
        } catch (JSONException e) {
            LOG.warn("JSON Parsing failed.", e);
            return null;
        }
    }

    @Nullable
    public static String getStringProperty(String jsonString, String propertyName) {

        if (jsonString == null || !jsonString.contains(propertyName)) {
            LOG.warn("JSON string doesn't contain " + propertyName);
            return null;
        }

        JSONObject object = getJSONObject(jsonString);

        if (object != null) {
            try {
                return object.getString(propertyName);
            } catch (JSONException e) {
                LOG.warn("JSON Parsing failed.", e);
            }
        }

        return null;
    }

    @Nullable
    public static Long getLongProperty(String jsonString, String propertyName) {

        if (jsonString == null || !jsonString.contains(propertyName)) {
            LOG.warn("JSON string doesn't contain " + propertyName);
            return null;
        }

        JSONObject object = getJSONObject(jsonString);

        if (object != null) {
            try {
                return object.getLong(propertyName);
            } catch (JSONException e) {
                LOG.warn("JSON Parsing failed.", e);
            }
        }

        return null;
    }

    /**
     * Convenience method to wrap the exception catching.
     * TODO validate to save generating an exception on invalid input
     * @param responseContents
     * @return JSON object or null
     */
    @Nullable
    public static JSONObject getJSONObject(String responseContents) {
        try {
            return new JSONObject(responseContents);
        } catch (JSONException e) {
            LOG.warn("JSON Parsing failed.", e);
            return null;
        }
    }

}
