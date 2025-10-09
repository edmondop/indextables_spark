/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.indextables.spark.util

import org.apache.spark.broadcast.Broadcast

import io.indextables.spark.storage.SplitCacheConfig
import org.slf4j.LoggerFactory

/** Utility functions for configuration management. */
object ConfigUtils {

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Statistics truncation configuration constants
  val STATS_TRUNCATION_ENABLED    = "spark.indextables.stats.truncation.enabled"
  val STATS_TRUNCATION_MAX_LENGTH = "spark.indextables.stats.truncation.maxLength"

  // Default values
  val DEFAULT_STATS_TRUNCATION_ENABLED    = true
  val DEFAULT_STATS_TRUNCATION_MAX_LENGTH = 256

  /**
   * Create a SplitCacheConfig from configuration map.
   *
   * This utility consolidates the duplicated cache configuration logic across partition readers, aggregate readers, and
   * other components.
   *
   * @param config
   *   Configuration map
   * @param tablePathOpt
   *   Optional table path for generating unique cache names
   * @return
   *   Configured SplitCacheConfig instance
   */
  def createSplitCacheConfig(
    config: Map[String, String],
    tablePathOpt: Option[String] = None
  ): SplitCacheConfig = {

    val configMap = config

    // Helper function to get config with defaults
    def getConfig(configKey: String, default: String = ""): String = {
      val value = configMap.getOrElse(configKey, default)
      Option(value).getOrElse(default)
    }

    def getConfigOption(configKey: String): Option[String] =
      // Try both the original key and lowercase version (CaseInsensitiveStringMap lowercases keys)
      configMap.get(configKey).orElse(configMap.get(configKey.toLowerCase))

    SplitCacheConfig(
      cacheName = {
        val configName = getConfig("spark.indextables.cache.name", "")
        if (configName.trim().nonEmpty) {
          configName.trim()
        } else {
          // Use table path as cache name for table-specific caching
          tablePathOpt match {
            case Some(tablePath) =>
              s"tantivy4spark-${tablePath.replaceAll("[^a-zA-Z0-9]", "_")}"
            case None =>
              s"tantivy4spark-default-${System.currentTimeMillis()}"
          }
        }
      },
      maxCacheSize = {
        val value = getConfig("spark.indextables.cache.maxSize", "200000000")
        try
          value.toLong
        catch {
          case e: NumberFormatException =>
            logger.error(s"Invalid numeric value for spark.indextables.cache.maxSize: '$value'")
            throw e
        }
      },
      maxConcurrentLoads = {
        val value = getConfig("spark.indextables.cache.maxConcurrentLoads", "8")
        try
          value.toInt
        catch {
          case e: NumberFormatException =>
            logger.error(s"Invalid numeric value for spark.indextables.cache.maxConcurrentLoads: '$value'")
            throw e
        }
      },
      enableQueryCache = getConfig("spark.indextables.cache.queryCache", "true").toBoolean,
      splitCachePath = getConfigOption("spark.indextables.cache.directoryPath")
        .orElse(SplitCacheConfig.getDefaultCachePath()),
      // AWS configuration from broadcast
      awsAccessKey = getConfigOption("spark.indextables.aws.accessKey"),
      awsSecretKey = getConfigOption("spark.indextables.aws.secretKey"),
      awsSessionToken = getConfigOption("spark.indextables.aws.sessionToken"),
      awsRegion = getConfigOption("spark.indextables.aws.region"),
      awsEndpoint = getConfigOption("spark.indextables.s3.endpoint"),
      awsPathStyleAccess = getConfigOption("spark.indextables.s3.pathStyleAccess").map(_.toBoolean),
      // Azure configuration from broadcast
      azureAccountName = getConfigOption("spark.indextables.azure.accountName"),
      azureAccountKey = getConfigOption("spark.indextables.azure.accountKey"),
      azureConnectionString = getConfigOption("spark.indextables.azure.connectionString"),
      azureEndpoint = getConfigOption("spark.indextables.azure.endpoint"),
      // GCP configuration from broadcast
      gcpProjectId = getConfigOption("spark.indextables.gcp.projectId"),
      gcpServiceAccountKey = getConfigOption("spark.indextables.gcp.serviceAccountKey"),
      gcpCredentialsFile = getConfigOption("spark.indextables.gcp.credentialsFile"),
      gcpEndpoint = getConfigOption("spark.indextables.gcp.endpoint")
    )
  }

  /**
   * Create a SplitCacheConfig from broadcast configuration.
   *
   * Wrapper method for backward compatibility - delegates to createSplitCacheConfig.
   *
   * @param broadcastConfig
   *   Broadcast variable containing configuration map
   * @param tablePathOpt
   *   Optional table path for generating unique cache names
   * @return
   *   Configured SplitCacheConfig instance
   */
  def createSplitCacheConfigFromBroadcast(
    broadcastConfig: Broadcast[Map[String, String]],
    tablePathOpt: Option[String] = None
  ): SplitCacheConfig =
    createSplitCacheConfig(broadcastConfig.value, tablePathOpt)

  /**
   * Get a boolean configuration value from the config map with a default value.
   *
   * @param config Configuration map
   * @param key Configuration key
   * @param defaultValue Default value if key not found
   * @return Boolean value
   */
  def getBoolean(config: Map[String, String], key: String, defaultValue: Boolean): Boolean =
    config.get(key).map(_.toBoolean).getOrElse(defaultValue)

  /**
   * Get an integer configuration value from the config map with a default value.
   *
   * @param config Configuration map
   * @param key Configuration key
   * @param defaultValue Default value if key not found
   * @return Integer value
   */
  def getInt(config: Map[String, String], key: String, defaultValue: Int): Int =
    config.get(key).map(_.toInt).getOrElse(defaultValue)

  /**
   * Get a string configuration value from the config map with a default value.
   *
   * @param config Configuration map
   * @param key Configuration key
   * @param defaultValue Default value if key not found
   * @return String value
   */
  def getString(config: Map[String, String], key: String, defaultValue: String): String =
    config.getOrElse(key, defaultValue)
}
