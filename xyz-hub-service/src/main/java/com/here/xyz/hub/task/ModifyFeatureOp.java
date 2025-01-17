/*
 * Copyright (C) 2017-2022 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.hub.task;

import static com.here.xyz.hub.task.FeatureTask.FeatureKey.AUTHOR;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.CREATED_AT;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.MUUID;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.PROPERTIES;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.PUUID;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.SPACE;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.UPDATED_AT;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.UUID;
import static com.here.xyz.hub.task.FeatureTask.FeatureKey.VERSION;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.here.xyz.XyzSerializable;
import com.here.xyz.hub.rest.HttpException;
import com.here.xyz.hub.task.ModifyFeatureOp.FeatureEntry;
import com.here.xyz.hub.util.diff.Patcher.ConflictResolution;
import com.here.xyz.models.geojson.implementation.Feature;
import com.here.xyz.models.geojson.implementation.XyzNamespace;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ModifyFeatureOp extends ModifyOp<Feature, FeatureEntry> {

  private final static String ON_FEATURE_NOT_EXISTS = "onFeatureNotExists";
  private final static String ON_FEATURE_EXISTS = "onFeatureExists";
  private final static String ON_MERGE_CONFLICT = "onMergeConflict";

  public boolean allowFeatureCreationWithUUID;

  public ModifyFeatureOp(List<FeatureEntry> featureEntries, boolean isTransactional) {
    this(featureEntries, isTransactional, false);
  }

  public ModifyFeatureOp(List<FeatureEntry> featureEntries, boolean isTransactional, boolean allowFeatureCreationWithUUID) {
    super(featureEntries, isTransactional);
    this.allowFeatureCreationWithUUID = allowFeatureCreationWithUUID;
  }

  /**
   * Converts a list of feature modifications input into a list of FeatureEntry where which entry contains information on how to handle
   * when the feature exists, not exists or conflict cases.
   * @param featureModifications A list of FeatureModifications of which each may have different settings for existence-handling and/or
   *  conflict-handling. If these settings are not specified at the FeatureModification the according other parameters (ifNotExists,
   *  ifExists, conflictResolution) of this constructor will be applied for that purpose.
   * @param ifNotExists defines the action in case the feature does not exist, possible values are available at {@link IfNotExists}
   * @param ifExists defines the action in case the feature already exists, possible values are available at {@link IfExists}
   * @param conflictResolution defines the action in case there is a conflict when processing the feature, possible values are available at {@link ConflictResolution}
   * @return the list of feature entries, which can be empty
   * @throws HttpException in case the parameters ifExists, ifNotExists and conflictResolution extracted from the featureModification contains invalid values
   */
  public static List<FeatureEntry> convertToFeatureEntries(List<Map<String, Object>> featureModifications, IfNotExists ifNotExists, IfExists ifExists, ConflictResolution conflictResolution)
      throws HttpException {
    if (featureModifications == null)
      return Collections.emptyList();

    final List<FeatureEntry> result = new ArrayList<>();
    for (Map<String, Object> fm : featureModifications) {
      IfNotExists ne =
          fm.get(ON_FEATURE_NOT_EXISTS) instanceof String ? IfNotExists.of((String) fm.get(ON_FEATURE_NOT_EXISTS)) : ifNotExists;
      IfExists e = fm.get(ON_FEATURE_EXISTS) instanceof String ? IfExists.of((String) fm.get(ON_FEATURE_EXISTS)) : ifExists;
      ConflictResolution cr = fm.get(ON_MERGE_CONFLICT) instanceof String ?
          ConflictResolution.of((String) fm.get(ON_MERGE_CONFLICT)) : conflictResolution;

      validateDefaultParams(ne, e, cr);

      List<String> featureIds = (List<String>) fm.get("featureIds");
      Map<String, Object> featureCollection = (Map<String, Object>) fm.get("featureData");
      List<Map<String, Object>> features = new ArrayList<>();

      if (featureCollection != null)
          features.addAll((List<Map<String, Object>>) featureCollection.get("features"));

      if (featureIds != null)
        features.addAll(idsToFeatures(featureIds));

      result.addAll(features.stream().map(feature -> new FeatureEntry(feature, ne, e, cr)).collect(Collectors.toList()));
    }

    return result;
  }

  private static void validateDefaultParams(IfNotExists ne, IfExists e, ConflictResolution cr) throws HttpException {
    if (ne == null) {
      throw new HttpException(HttpResponseStatus.BAD_REQUEST, "Invalid value provided for parameter onFeatureNotExists");
    }

    if (e == null) {
      throw new HttpException(HttpResponseStatus.BAD_REQUEST, "Invalid value provided for parameter onFeatureExists");
    }

    if (cr == null) {
      throw new HttpException(HttpResponseStatus.BAD_REQUEST, "Invalid value provided for parameter onMergeConflict");
    }
  }

  private static List<Map<String, Object>> idsToFeatures(List<String> featureIds) {
    return featureIds.stream().map(fId -> new JsonObject().put("id", fId).getMap()).collect(Collectors.toList());
  }

  public static class FeatureEntry extends ModifyOp.Entry<Feature> {
    public FeatureEntry(Map<String, Object> input, IfNotExists ifNotExists, IfExists ifExists, ConflictResolution cr) {
      super(input, ifNotExists, ifExists, cr);
    }

    @Override
    public Feature fromMap(Map<String, Object> map) throws ModifyOpError, HttpException {
      try {
        return XyzSerializable.fromMap(map, Feature.class);
      } catch (Exception e) {
        try {
          throw new HttpException(HttpResponseStatus.BAD_REQUEST,
              "Unable to create a Feature from the provided input: " + XyzSerializable.DEFAULT_MAPPER.get().writeValueAsString(map));
        } catch (JsonProcessingException jsonProcessingException) {
          throw new HttpException(HttpResponseStatus.BAD_REQUEST,
              "Unable to create a Feature from the provided input. id: " + map.get("id") + ",type: " + map.get("type"));
        }
      }
    }

    @Override
    public Map<String, Object> toMap(Feature record) throws ModifyOpError, HttpException {
      return filterMetadata(record.asMap());
    }

    @Override
    protected String getUuid(Map<String, Object> feature) {
      try {
        return new JsonObject(feature).getJsonObject(PROPERTIES).getJsonObject(XyzNamespace.XYZ_NAMESPACE).getString(UUID);
        //NOTE: The following is a temporary implementation for backwards compatibility for legacy spaces with versionsToKeep = 0
        //return uuid == null ? "" + getVersion(feature) : uuid;
      }
      catch (Exception e) {
        return null;
      }
    }

    @Override
    protected long getVersion(Map<String, Object> feature) {
      try {
        return new JsonObject(feature).getJsonObject(PROPERTIES).getJsonObject(XyzNamespace.XYZ_NAMESPACE).getLong(VERSION, -1L);
      }
      catch (Exception e) {
        return -1;
      }
    }

    @Override
    protected String getUuid(Feature input) {
      try {
        return input.getProperties().getXyzNamespace().getUuid();
      } catch (Exception e) {
        return null;
      }
    }

    @Override
    protected long getVersion(Feature input) {
      try {
        return input.getProperties().getXyzNamespace().getVersion();
      } catch (Exception e) {
        return -1;
      }
    }

    public String getId(Feature record) {
      return record == null ? null : record.getId();
    }

    @Override
    public Map<String, Object> filterMetadata(Map<String, Object> map) {
      return filter(map, metadataFilter);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> metadataFilter = new JsonObject()
        .put(PROPERTIES, new JsonObject()
            .put(XyzNamespace.XYZ_NAMESPACE,
                new JsonObject()
                    .put(SPACE, true)
                    .put(CREATED_AT, true)
                    .put(UPDATED_AT, true)
                    .put(UUID, true)
                    .put(PUUID, true)
                    .put(MUUID, true)
                    .put(VERSION, true)
                    .put(AUTHOR, true))
        ).mapTo(Map.class);
  }

  /**
   * Validates whether the feature can be created based on the space's flag allowFeatureCreationWithUUID.
   * Creation of features using UUID in the payload should always return an error, however at the moment, due to a bug,
   * the creation succeeds.
   * @param entry
   * @throws ModifyOpError
   */
  @Override
  public void validateCreate(Entry<Feature> entry) throws ModifyOpError {
    if (!allowFeatureCreationWithUUID && entry.inputUUID != null)
      throw new ModifyOpError(
          "The feature with id " + entry.input.get("id") + " cannot be created. Property UUID should not be provided as input.");
  }
}
