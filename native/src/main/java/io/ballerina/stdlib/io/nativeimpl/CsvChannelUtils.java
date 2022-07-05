/*
 * Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.io.nativeimpl;

import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.StructureType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BStream;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;
import io.ballerina.stdlib.io.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.ballerina.stdlib.io.nativeimpl.RecordChannelUtils.getAllRecords;
import static io.ballerina.stdlib.io.nativeimpl.RecordChannelUtils.hasNext;
import static io.ballerina.stdlib.io.utils.IOConstants.CSV_RETURN_TYPE;
import static io.ballerina.stdlib.io.utils.IOUtils.getIOPackage;


/**
 * This class hold Java inter-ops bridging functions for io# *CSVChannel/*RTextRecordChannel.
 *
 */

public class CsvChannelUtils {
    private static final Logger log = LoggerFactory.getLogger(CsvChannelUtils.class);
    
    public static Object fileReadCsv(BString path, int skipHeaders, BTypedesc typeDesc) {
        BObject byteChannel = (BObject) ByteChannelUtils.openReadableFile(path);

        BObject characterChannel = ValueCreator.createObjectValue(getIOPackage(), "ReadableByteChannel");
        CharacterChannelUtils.initCharacterChannel(characterChannel, byteChannel, StringUtils.fromString("UTF-8"));

        BString fs = StringUtils.fromString(",");
        BString rs = StringUtils.fromString("");
        BString format = StringUtils.fromString("CSV");
        BObject textRecordChannel = ValueCreator.createObjectValue(getIOPackage(), "ReadableByteChannel");
        RecordChannelUtils.initRecordChannel(textRecordChannel, characterChannel, fs, rs, format);

        textRecordChannel.addNativeData(CSV_RETURN_TYPE, typeDesc);

        while (hasNext(textRecordChannel)) {

            return getAllRecords(textRecordChannel, skipHeaders, typeDesc);
        }
        return null;
    }

    public static BStream createCsvAsStream(BString path, BTypedesc typeDesc) {
        Type describingType = typeDesc.getDescribingType();
        BObject byteChannel = (BObject) ByteChannelUtils.openReadableFile(path);

        BObject characterChannel = ValueCreator.createObjectValue(getIOPackage(), "ReadableByteChannel");
        CharacterChannelUtils.initCharacterChannel(characterChannel, byteChannel, StringUtils.fromString("UTF-8"));

        BString fs = StringUtils.fromString(",");
        BString rs = StringUtils.fromString("");
        BString format = StringUtils.fromString("CSV");
        BObject textRecordChannel = ValueCreator.createObjectValue(getIOPackage(), "ReadableByteChannel");
        RecordChannelUtils.initRecordChannel(textRecordChannel, characterChannel, fs, rs, format);
        BObject recordIterator = ValueCreator.createObjectValue(getIOPackage(), "CsvIterator");

        recordIterator.addNativeData(CSV_RETURN_TYPE, typeDesc);
        recordIterator.addNativeData("ITERATOR_NAME", textRecordChannel);

        BStream out = ValueCreator.createStreamValue(
                TypeCreator.createStreamType(describingType), recordIterator);

        return out;
    }

    public static Map<String, Object> getStruct(String[] fields, final StructureType structType) {
        Map<String, Field> internalStructFields = structType.getFields();
        int fieldLength = internalStructFields.size();
        Map<String, Object> struct = null;
        if (fields.length > 0) {
            Iterator<Map.Entry<String, Field>> itr = internalStructFields.entrySet().iterator();
            struct = new HashMap<>();
            for (int i = 0; i < fieldLength; i++) {
                final Field internalStructField = itr.next().getValue();
                final int type = internalStructField.getFieldType().getTag();
                String fieldName = internalStructField.getFieldName();
                if (fields.length > i) {
                    String value = fields[i];
                    switch (type) {
                        case TypeTags.INT_TAG:
                        case TypeTags.FLOAT_TAG:
                        case TypeTags.STRING_TAG:
                        case TypeTags.DECIMAL_TAG:
                        case TypeTags.BOOLEAN_TAG:
                            populateRecord(type, struct, fieldName, value);
                            break;
                        case TypeTags.UNION_TAG:
                            List<Type> members = ((UnionType) internalStructField.getFieldType()).getMemberTypes();
                            if (members.get(0).getTag() == TypeTags.NULL_TAG) {
                                populateRecord(members.get(1).getTag(), struct, fieldName, value);
                            } else if (members.get(1).getTag() == TypeTags.NULL_TAG) {
                                populateRecord(members.get(0).getTag(), struct, fieldName, value);
                            } else {
                                throw IOUtils.createError("unsupported nillable field for value: " + value);
                            }
                            break;
                        default:
                            throw IOUtils.createError(
                                    "type casting support only for int, float, boolean and string. "
                                            + "Invalid value for the struct field: " + value);
                    }
                } else {
                    struct.put(fieldName, null);
                }
            }
        }
        return struct;
    }

    private static void populateRecord(int type, Map<String, Object> struct, String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            struct.put(fieldName, null);
        } else {
            String trimmedValue = value.trim();
            switch (type) {
                case TypeTags.INT_TAG:
                    struct.put(fieldName, Long.parseLong(trimmedValue));
                    return;
                case TypeTags.FLOAT_TAG:
                    struct.put(fieldName, Double.parseDouble(trimmedValue));
                    break;
                case TypeTags.DECIMAL_TAG:
                    struct.put(fieldName, ValueCreator.createDecimalValue(trimmedValue));
                    break;
                case TypeTags.STRING_TAG:
                    struct.put(fieldName, trimmedValue);
                    break;
                case TypeTags.BOOLEAN_TAG:
                    struct.put(fieldName, Boolean.parseBoolean(trimmedValue));
                    break;
                default:
                    throw IOUtils.createError("type casting support only for int, float, boolean and string. "
                            + "Invalid value for the struct field: " + value);
            }
        }
    }
}
