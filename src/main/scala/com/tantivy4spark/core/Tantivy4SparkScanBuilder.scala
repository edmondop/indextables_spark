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


package com.tantivy4spark.core

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.connector.read.{Scan, ScanBuilder, SupportsPushDownFilters, SupportsPushDownV2Filters, SupportsPushDownRequiredColumns, SupportsPushDownLimit, SupportsPushDownAggregates}
import org.apache.spark.sql.connector.expressions.aggregate.Aggregation
import org.apache.spark.sql.connector.expressions.filter.Predicate
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap
import com.tantivy4spark.transaction.TransactionLog
import org.apache.spark.broadcast.Broadcast
import org.slf4j.LoggerFactory

class Tantivy4SparkScanBuilder(
    sparkSession: SparkSession,
    transactionLog: TransactionLog,
    schema: StructType,
    options: CaseInsensitiveStringMap,
    broadcastConfig: Broadcast[Map[String, String]]
) extends ScanBuilder
    with SupportsPushDownFilters
    with SupportsPushDownV2Filters
    with SupportsPushDownRequiredColumns
    with SupportsPushDownLimit
    with SupportsPushDownAggregates {

  private val logger = LoggerFactory.getLogger(classOf[Tantivy4SparkScanBuilder])

  println(s"🔍 SCAN BUILDER CREATED: V2 DataSource with SupportsPushDownAggregates interface")
  logger.info(s"🔍 SCAN BUILDER CREATED: V2 DataSource with SupportsPushDownAggregates interface")
  // Filters that have been pushed down and will be applied by the data source
  private var _pushedFilters = Array.empty[Filter]
  private var requiredSchema = schema
  private var _limit: Option[Int] = None

  // Aggregate pushdown state
  private var _pushedAggregation: Option[Aggregation] = None
  private var _pushedGroupBy: Option[Array[String]] = None

  // Generate instance key for this ScanBuilder to retrieve IndexQueries
  private val instanceKey = {
    val tablePath = transactionLog.getTablePath().toString
    val executionId = Option(sparkSession.sparkContext.getLocalProperty("spark.sql.execution.id"))
    Tantivy4SparkScanBuilder.generateInstanceKey(tablePath, executionId)
  }


  override def build(): Scan = {
    println(s"🔍 BUILD: ScanBuilder.build() called - aggregation: ${_pushedAggregation.isDefined}, filters: ${_pushedFilters.length}")

    // Check if we have aggregate pushdown
    _pushedAggregation match {
      case Some(aggregation) =>
        println(s"🔍 BUILD: Creating aggregate scan for pushed aggregation: $aggregation")
        logger.info(s"🔍 BUILD: Creating aggregate scan for pushed aggregation")
        // Return aggregate scan (will implement this next)
        createAggregateScan(aggregation)
      case None =>
        // Regular scan
        println(s"🔍 BUILD: Creating regular scan (no aggregation pushdown)")
        logger.info(s"🔍 BUILD: Creating regular scan (no aggregation pushdown)")
        // DIRECT EXTRACTION: Extract IndexQuery expressions directly from the current logical plan
        val extractedIndexQueryFilters = extractIndexQueriesFromCurrentPlan()

        logger.error(s"🔍 BUILD DEBUG: Extracted ${extractedIndexQueryFilters.length} IndexQuery filters directly from plan")
        extractedIndexQueryFilters.foreach(filter => logger.error(s"  - Extracted IndexQuery: $filter"))

        println(s"🔍 BUILD DEBUG: Creating Tantivy4SparkScan with ${_pushedFilters.length} pushed filters")
        _pushedFilters.foreach(filter => println(s"  - Creating scan with filter: $filter"))
        new Tantivy4SparkScan(sparkSession, transactionLog, requiredSchema, _pushedFilters, options, _limit, broadcastConfig, extractedIndexQueryFilters)
    }
  }

  /**
   * Create an aggregate scan for pushed aggregations.
   */
  private def createAggregateScan(aggregation: Aggregation): Scan = {
    _pushedGroupBy match {
      case Some(groupByColumns) =>
        // GROUP BY aggregation
        logger.info(s"🔍 AGGREGATE SCAN: Creating GROUP BY aggregation scan for columns: ${groupByColumns.mkString(", ")}")
        createGroupByAggregateScan(aggregation, groupByColumns)
      case None =>
        // Simple aggregation without GROUP BY
        // Check if we can use transaction log count optimization
        if (canUseTransactionLogCount(aggregation)) {
          println(s"🔍 AGGREGATE SCAN: Using transaction log count optimization")
          logger.info(s"🔍 AGGREGATE SCAN: Using transaction log count optimization")
          createTransactionLogCountScan(aggregation)
        } else {
          println(s"🔍 AGGREGATE SCAN: Creating simple aggregation scan")
          logger.info(s"🔍 AGGREGATE SCAN: Creating simple aggregation scan")
          createSimpleAggregateScan(aggregation)
        }
    }
  }

  /**
   * Create a GROUP BY aggregation scan.
   */
  private def createGroupByAggregateScan(aggregation: Aggregation, groupByColumns: Array[String]): Scan = {
    logger.info(s"🔍 GROUP BY SCAN: Creating GROUP BY scan for aggregation: $aggregation")
    logger.info(s"🔍 GROUP BY SCAN: GROUP BY columns: ${groupByColumns.mkString(", ")}")

    // TODO: Verify that GROUP BY aggregations are also using the shared data skipping function
    // For now, create a specialized GROUP BY scan
    // This will be implemented as Tantivy4SparkGroupByAggregateScan
    new Tantivy4SparkGroupByAggregateScan(
      sparkSession,
      transactionLog,
      schema,
      _pushedFilters,
      options,
      broadcastConfig,
      aggregation,
      groupByColumns
    )
  }

  /**
   * Create a simple aggregation scan (no GROUP BY).
   */
  private def createSimpleAggregateScan(aggregation: Aggregation): Scan = {
    logger.info(s"🔍 AGGREGATE SCAN: Creating simple aggregation scan for: ${aggregation.aggregateExpressions.mkString(", ")}")

    val extractedIndexQueryFilters = extractIndexQueriesFromCurrentPlan()
    new Tantivy4SparkSimpleAggregateScan(sparkSession, transactionLog, schema, _pushedFilters, options, broadcastConfig, aggregation)
  }

  /**
   * Check if we can optimize COUNT queries using transaction log.
   */
  private def canUseTransactionLogCount(aggregation: Aggregation): Boolean = {
    import org.apache.spark.sql.connector.expressions.aggregate.{Count, CountStar}

    println(s"🔍 SCAN BUILDER: canUseTransactionLogCount called with ${aggregation.aggregateExpressions.length} expressions")
    aggregation.aggregateExpressions.foreach(expr => println(s"🔍 SCAN BUILDER: Aggregate expression: $expr (${expr.getClass.getSimpleName})"))
    println(s"🔍 SCAN BUILDER: Number of pushed filters: ${_pushedFilters.length}")
    _pushedFilters.foreach(filter => println(s"🔍 SCAN BUILDER: Pushed filter: $filter"))

    val result = aggregation.aggregateExpressions.length == 1 && {
      aggregation.aggregateExpressions.head match {
        case _: Count =>
          // Check if we only have partition filters or no filters
          val hasOnlyPartitionFilters = _pushedFilters.forall(isPartitionFilter)
          println(s"🔍 SCAN BUILDER: COUNT - Can use transaction log optimization: $hasOnlyPartitionFilters")
          hasOnlyPartitionFilters
        case _: CountStar =>
          // Check if we only have partition filters or no filters
          val hasOnlyPartitionFilters = _pushedFilters.forall(isPartitionFilter)
          println(s"🔍 SCAN BUILDER: COUNT(*) - Can use transaction log optimization: $hasOnlyPartitionFilters")
          hasOnlyPartitionFilters
        case _ =>
          println(s"🔍 SCAN BUILDER: Not a COUNT aggregation, cannot use transaction log")
          false
      }
    }
    println(s"🔍 SCAN BUILDER: canUseTransactionLogCount returning: $result")
    result
  }

  /**
   * Create a specialized scan that returns count from transaction log.
   */
  private def createTransactionLogCountScan(aggregation: Aggregation): Scan = {
    new TransactionLogCountScan(sparkSession, transactionLog, _pushedFilters, options, broadcastConfig)
  }

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    println(s"🔍 PUSHFILTERS DEBUG: pushFilters called with ${filters.length} filters")
    logger.error(s"🔍 PUSHFILTERS DEBUG: pushFilters called with ${filters.length} filters")
    filters.foreach(filter => {
      println(s"  - Input filter: $filter (${filter.getClass.getSimpleName})")
      logger.error(s"  - Input filter: $filter (${filter.getClass.getSimpleName})")
    })

    // Since IndexQuery expressions are now handled directly by the V2IndexQueryExpressionRule,
    // we only need to handle regular Spark filters here.
    val (supported, unsupported) = filters.partition(isSupportedFilter)

    // Store supported filters
    _pushedFilters = supported

    println(s"🔍 PUSHFILTERS DEBUG: Stored ${_pushedFilters.length} pushed filters")
    _pushedFilters.foreach(filter => println(s"  - Stored filter: $filter"))

    logger.info(s"Filter pushdown summary:")
    logger.info(s"  - ${supported.length} filters FULLY SUPPORTED by data source (will NOT be re-evaluated by Spark)")
    supported.foreach(filter => logger.info(s"    ✓ PUSHED: $filter"))

    logger.info(s"  - ${unsupported.length} filters NOT SUPPORTED (will be re-evaluated by Spark after reading)")
    unsupported.foreach(filter => logger.info(s"    ✗ NOT PUSHED: $filter"))

    // Return only unsupported filters - Spark will re-evaluate these after reading data
    unsupported
  }

  override def pushPredicates(predicates: Array[Predicate]): Array[Predicate] = {
    println(s"🔍 PUSHPREDICATES DEBUG: pushPredicates called with ${predicates.length} predicates")
    logger.info(s"🔍 PUSHPREDICATES DEBUG: pushPredicates called with ${predicates.length} predicates")
    predicates.foreach(predicate => {
      println(s"  - V2 Predicate: $predicate (${predicate.getClass.getSimpleName})")
      logger.info(s"  - V2 Predicate: $predicate (${predicate.getClass.getSimpleName})")
    })
    predicates.foreach(predicate => logger.info(s"  - Input predicate: $predicate (${predicate.getClass.getSimpleName})"))

    // Convert predicates that we can handle and extract IndexQuery information
    val (supported, unsupported) = predicates.partition(isSupportedPredicate)

    // Store supported predicates - for now, just log them
    logger.info(s"Predicate pushdown summary:")
    logger.info(s"  - ${supported.length} predicates FULLY SUPPORTED by data source (will NOT be re-evaluated by Spark)")
    supported.foreach(predicate => logger.info(s"    ✓ PUSHED: $predicate"))

    logger.info(s"  - ${unsupported.length} predicates NOT SUPPORTED (will be re-evaluated by Spark after reading)")
    unsupported.foreach(predicate => logger.info(s"    ✗ NOT PUSHED: $predicate"))

    // Return only unsupported predicates - Spark will re-evaluate these
    unsupported
  }

  override def pushedFilters(): Array[Filter] = _pushedFilters

  override def pushedPredicates(): Array[Predicate] = Array.empty  // V2 interface method

  override def pruneColumns(requiredSchema: StructType): Unit = {
    this.requiredSchema = requiredSchema
    logger.info(s"Pruned columns to: ${requiredSchema.fieldNames.mkString(", ")}")
  }

  override def pushLimit(limit: Int): Boolean = {
    _limit = Some(limit)
    logger.info(s"Pushed limit: $limit")
    true // We support limit pushdown
  }

  override def supportCompletePushDown(aggregation: Aggregation): Boolean = {
    // Return false to allow Spark to handle final aggregation combining partial results
    // This enables proper distributed aggregation where:
    // - AVG is transformed to SUM + COUNT by Spark
    // - Partial results from each partition are combined correctly
    logger.info(s"🔍 AGGREGATE PUSHDOWN: supportCompletePushDown called - returning false for distributed aggregation")
    false
  }

  override def pushAggregation(aggregation: Aggregation): Boolean = {
    println(s"🔍 AGGREGATE PUSHDOWN: Received aggregation request: $aggregation")
    logger.info(s"🔍 AGGREGATE PUSHDOWN: Received aggregation request: $aggregation")

    // Check if this is a GROUP BY aggregation
    val groupByExpressions = aggregation.groupByExpressions()
    val hasGroupBy = groupByExpressions != null && groupByExpressions.nonEmpty

    println(s"🔍 GROUP BY CHECK: hasGroupBy = $hasGroupBy")
    if (hasGroupBy) {
      println(s"🔍 GROUP BY DETECTED: Found ${groupByExpressions.length} GROUP BY expressions")
      logger.info(s"🔍 GROUP BY DETECTED: Found ${groupByExpressions.length} GROUP BY expressions")
      groupByExpressions.foreach { expr =>
        logger.info(s"🔍 GROUP BY EXPRESSION: $expr")
      }

      // Extract GROUP BY column names
      val groupByColumns = groupByExpressions.map(extractFieldNameFromExpression)
      logger.info(s"🔍 GROUP BY COLUMNS: ${groupByColumns.mkString(", ")}")

      // Validate GROUP BY columns are supported - throw exception if not
      println(s"🔍 GROUP BY VALIDATION: About to check areGroupByColumnsSupported")
      validateGroupByColumnsOrThrow(groupByColumns)
      println(s"🔍 GROUP BY VALIDATION: areGroupByColumnsSupported passed")

      // Check if aggregation is compatible with GROUP BY - throw exception if not
      println(s"🔍 GROUP BY VALIDATION: About to check isAggregationCompatibleWithGroupBy")
      validateAggregationCompatibilityOrThrow(aggregation)
      println(s"🔍 GROUP BY VALIDATION: isAggregationCompatibleWithGroupBy passed")

      // Store GROUP BY information
      _pushedGroupBy = Some(groupByColumns)
      logger.info(s"🔍 GROUP BY PUSHDOWN: ACCEPTED - GROUP BY will be pushed down")
    } else {
      logger.info(s"🔍 SIMPLE AGGREGATION: No GROUP BY expressions found")
    }

    // Validate aggregation is supported (both simple and GROUP BY)
    println(s"🔍 AGGREGATE PUSHDOWN: About to check isAggregationSupported")
    if (!isAggregationSupported(aggregation)) {
      println(s"🔍 AGGREGATE PUSHDOWN: REJECTED - aggregation not supported")
      logger.info(s"🔍 AGGREGATE PUSHDOWN: REJECTED - aggregation not supported")
      return false
    }
    println(s"🔍 AGGREGATE PUSHDOWN: isAggregationSupported passed")

    // Check if filters are compatible with aggregate pushdown
    println(s"🔍 AGGREGATE PUSHDOWN: About to check areFiltersCompatibleWithAggregation")
    if (!areFiltersCompatibleWithAggregation()) {
      println(s"🔍 AGGREGATE PUSHDOWN: REJECTED - filters not compatible")
      logger.info(s"🔍 AGGREGATE PUSHDOWN: REJECTED - filters not compatible")
      return false
    }
    println(s"🔍 AGGREGATE PUSHDOWN: areFiltersCompatibleWithAggregation passed")

    // Store for later use in build()
    _pushedAggregation = Some(aggregation)
    println(s"🔍 AGGREGATE PUSHDOWN: ACCEPTED - aggregation will be pushed down")
    logger.info(s"🔍 AGGREGATE PUSHDOWN: ACCEPTED - aggregation will be pushed down")
    println(s"🔍 AGGREGATE PUSHDOWN: Returning true")
    true
  }


  /**
   * Extract field name from Spark expression for GROUP BY detection.
   */
  private def extractFieldNameFromExpression(expression: org.apache.spark.sql.connector.expressions.Expression): String = {
    // Use toString and try to extract field name
    val exprStr = expression.toString
    println(s"🔍 FIELD EXTRACTION: Expression string: '$exprStr'")
    println(s"🔍 FIELD EXTRACTION: Expression class: ${expression.getClass.getSimpleName}")

    // Check if it's a FieldReference by class name
    if (expression.getClass.getSimpleName == "FieldReference") {
      // For FieldReference, toString() returns the field name directly
      val fieldName = exprStr
      println(s"🔍 FIELD EXTRACTION: Extracted field name from FieldReference: '$fieldName'")
      fieldName
    } else if (exprStr.startsWith("FieldReference(")) {
      // Fallback for other FieldReference string formats
      val pattern = """FieldReference\(([^)]+)\)""".r
      pattern.findFirstMatchIn(exprStr) match {
        case Some(m) =>
          val fieldName = m.group(1)
          println(s"🔍 FIELD EXTRACTION: Extracted field name from pattern: '$fieldName'")
          fieldName
        case None =>
          println(s"🔍 FIELD EXTRACTION: Could not extract field name from expression: $expression")
          logger.warn(s"Could not extract field name from expression: $expression")
          "unknown_field"
      }
    } else {
      println(s"🔍 FIELD EXTRACTION: Unsupported expression type for field extraction: $expression")
      logger.warn(s"Unsupported expression type for field extraction: $expression")
      "unknown_field"
    }
  }

  private def isSupportedFilter(filter: Filter): Boolean = {
    import org.apache.spark.sql.sources._

    filter match {
      case EqualTo(attribute, _) => isFieldSuitableForExactMatching(attribute)
      case EqualNullSafe(attribute, _) => isFieldSuitableForExactMatching(attribute)
      case _: GreaterThan => false  // Range queries require fast fields - defer to Spark
      case _: GreaterThanOrEqual => false  // Range queries require fast fields - defer to Spark
      case _: LessThan => false  // Range queries require fast fields - defer to Spark
      case _: LessThanOrEqual => false  // Range queries require fast fields - defer to Spark
      case _: In => true
      case _: IsNull => true
      case _: IsNotNull => true
      case And(left, right) => isSupportedFilter(left) && isSupportedFilter(right)
      case Or(left, right) => isSupportedFilter(left) && isSupportedFilter(right)
      case Not(child) => isSupportedFilter(child)  // NOT is supported only if child is supported
      case _: StringStartsWith => false  // Tantivy does best-effort, Spark applies final filtering
      case _: StringEndsWith => false   // Tantivy does best-effort, Spark applies final filtering
      case _: StringContains => true
      case _ => false
    }
  }

  private def isSupportedPredicate(predicate: Predicate): Boolean = {
    // For V2 predicates, we need to inspect the actual predicate type
    // For now, let's accept all predicates and see what we get
    logger.info(s"🔍 isSupportedPredicate: Checking predicate $predicate")

    // TODO: Implement proper predicate type checking based on Spark's V2 Predicate types
    true  // Accept all for now to see what comes through
  }

  /**
   * Check if a field is suitable for exact matching at the data source level.
   * String fields (raw tokenizer) support exact matching.
   * Text fields (default tokenizer) should be filtered by Spark for exact matches.
   */
  private def isFieldSuitableForExactMatching(attribute: String): Boolean = {
    // Check the field type configuration from broadcast options
    val broadcastConfigMap = broadcastConfig.value
    val fieldTypeKey = s"spark.indextables.indexing.typemap.$attribute"
    val fieldType = broadcastConfigMap.get(fieldTypeKey)

    fieldType match {
      case Some("string") =>
        logger.info(s"🔍 Field '$attribute' configured as 'string' - supporting exact matching")
        true
      case Some("text") =>
        logger.info(s"🔍 Field '$attribute' configured as 'text' - deferring exact matching to Spark")
        false
      case Some(other) =>
        logger.info(s"🔍 Field '$attribute' configured as '$other' - supporting exact matching")
        true
      case None =>
        // No explicit configuration - assume string type (new default)
        logger.info(s"🔍 Field '$attribute' has no type configuration - assuming 'string', supporting exact matching")
        true
    }
  }


  /**
   * Extract IndexQuery expressions directly using the companion object storage.
   * This eliminates the need for global registry by using instance-scoped storage.
   */
  private def extractIndexQueriesFromCurrentPlan(): Array[Any] = {
    logger.error(s"🔍 EXTRACT DEBUG: Starting direct IndexQuery extraction using instance key: $instanceKey")

    // Method 1: Get IndexQueries stored by V2IndexQueryExpressionRule for this instance
    val storedQueries = Tantivy4SparkScanBuilder.getIndexQueries(instanceKey)
    if (storedQueries.nonEmpty) {
      logger.error(s"🔍 EXTRACT DEBUG: Found ${storedQueries.length} IndexQuery filters from instance storage")
      storedQueries.foreach(q => logger.error(s"  - Instance IndexQuery: $q"))
      return storedQueries.toArray
    }

    // Method 2: Fall back to registry (temporary until we fully eliminate it)
    import com.tantivy4spark.filters.IndexQueryRegistry
    IndexQueryRegistry.getCurrentQueryId() match {
      case Some(queryId) =>
        val registryQueries = IndexQueryRegistry.getIndexQueriesForQuery(queryId)
        if (registryQueries.nonEmpty) {
          logger.error(s"🔍 EXTRACT DEBUG: Found ${registryQueries.length} IndexQuery filters from registry as fallback")
          registryQueries.foreach(q => logger.error(s"  - Registry IndexQuery: $q"))
          return registryQueries.toArray
        }
      case None =>
        logger.error(s"🔍 EXTRACT DEBUG: No query ID available in registry")
    }

    logger.error(s"🔍 EXTRACT DEBUG: No IndexQuery filters found using any method")
    Array.empty[Any]
  }

  /**
   * Check if the aggregation is supported for pushdown.
   */
  private def isAggregationSupported(aggregation: Aggregation): Boolean = {
    import org.apache.spark.sql.connector.expressions.aggregate.{Count, CountStar, Sum, Avg, Min, Max}

    println(s"🔍 AGGREGATE VALIDATION: Checking ${aggregation.aggregateExpressions.length} aggregate expressions")
    aggregation.aggregateExpressions.zipWithIndex.foreach { case (expr, index) =>
      println(s"🔍 AGGREGATE VALIDATION: Expression $index: $expr (${expr.getClass.getSimpleName})")
    }

    val result = aggregation.aggregateExpressions.forall { expr =>
      val isSupported = expr match {
        case _: Count =>
          println(s"🔍 AGGREGATE VALIDATION: COUNT aggregation is supported")
          logger.info(s"🔍 AGGREGATE VALIDATION: COUNT aggregation is supported")
          true
        case _: CountStar =>
          println(s"🔍 AGGREGATE VALIDATION: COUNT(*) aggregation is supported")
          logger.info(s"🔍 AGGREGATE VALIDATION: COUNT(*) aggregation is supported")
          true
        case sum: Sum =>
          val fieldName = getFieldName(sum.column)
          val isSupported = isNumericFastField(fieldName)
          logger.info(s"🔍 AGGREGATE VALIDATION: SUM on field '$fieldName' supported: $isSupported")
          isSupported
        case avg: Avg =>
          val fieldName = getFieldName(avg.column)
          val isSupported = isNumericFastField(fieldName)
          logger.info(s"🔍 AGGREGATE VALIDATION: AVG on field '$fieldName' supported: $isSupported")
          isSupported
        case min: Min =>
          val fieldName = getFieldName(min.column)
          val isSupported = isNumericFastField(fieldName)
          logger.info(s"🔍 AGGREGATE VALIDATION: MIN on field '$fieldName' supported: $isSupported")
          isSupported
        case max: Max =>
          val fieldName = getFieldName(max.column)
          val isSupported = isNumericFastField(fieldName)
          logger.info(s"🔍 AGGREGATE VALIDATION: MAX on field '$fieldName' supported: $isSupported")
          isSupported
        case other =>
          println(s"🔍 AGGREGATE VALIDATION: Unsupported aggregation type: ${other.getClass.getSimpleName}")
          logger.info(s"🔍 AGGREGATE VALIDATION: Unsupported aggregation type: ${other.getClass.getSimpleName}")
          false
      }
      println(s"🔍 AGGREGATE VALIDATION: Expression $expr supported: $isSupported")
      isSupported
    }
    println(s"🔍 AGGREGATE VALIDATION: Overall aggregation supported: $result")
    result
  }

  /**
   * Extract field name from an aggregate expression column.
   */
  private def getFieldName(column: org.apache.spark.sql.connector.expressions.Expression): String = {
    // Use existing extractFieldNameFromExpression method
    val fieldName = extractFieldNameFromExpression(column)
    if (fieldName == "unknown_field") {
      throw new UnsupportedOperationException(s"Complex column expressions not supported for aggregation: $column")
    }
    fieldName
  }

  /**
   * Check if a field is numeric and marked as fast in the schema.
   */
  private def isNumericFastField(fieldName: String): Boolean = {
    // Get actual fast fields from the schema/docMappingJson
    val fastFields = getActualFastFieldsFromSchema()

    if (!fastFields.contains(fieldName)) {
      logger.info(s"🔍 FAST FIELD VALIDATION: Field '$fieldName' is not marked as fast in schema, rejecting aggregate pushdown")
      return false
    }

    // Check if field is numeric
    schema.fields.find(_.name == fieldName) match {
      case Some(field) if isNumericType(field.dataType) =>
        logger.info(s"🔍 FAST FIELD VALIDATION: Field '$fieldName' is numeric and fast - supported")
        true
      case Some(field) =>
        logger.info(s"🔍 FAST FIELD VALIDATION: Field '$fieldName' is not numeric (${field.dataType}) - not supported")
        false
      case None =>
        logger.info(s"🔍 FAST FIELD VALIDATION: Field '$fieldName' not found in schema - not supported")
        false
    }
  }

  /**
   * Check if a DataType is numeric.
   */
  private def isNumericType(dataType: org.apache.spark.sql.types.DataType): Boolean = {
    import org.apache.spark.sql.types._
    dataType match {
      case _: IntegerType | _: LongType | _: FloatType | _: DoubleType | _: DecimalType => true
      case _ => false
    }
  }


  /**
   * Check if current filters are compatible with aggregate pushdown.
   * This validates fast field configuration and throws exceptions for validation failures.
   */
  private def areFiltersCompatibleWithAggregation(): Boolean = {
    // If there are no filters, aggregation is compatible
    if (_pushedFilters.isEmpty) {
      return true
    }

    // Check if filter types are supported and if filter fields are fast fields
    _pushedFilters.foreach { filter =>
      val isFilterTypeSupported = filter match {
        // Supported filter types
        case _: org.apache.spark.sql.sources.EqualTo => true
        case _: org.apache.spark.sql.sources.GreaterThan => true
        case _: org.apache.spark.sql.sources.LessThan => true
        case _: org.apache.spark.sql.sources.GreaterThanOrEqual => true
        case _: org.apache.spark.sql.sources.LessThanOrEqual => true
        case _: org.apache.spark.sql.sources.In => true
        case _: org.apache.spark.sql.sources.IsNull => true
        case _: org.apache.spark.sql.sources.IsNotNull => true
        case _: org.apache.spark.sql.sources.And => true
        case _: org.apache.spark.sql.sources.Or => true
        case _: org.apache.spark.sql.sources.StringContains => true
        case _: org.apache.spark.sql.sources.StringStartsWith => true
        case _: org.apache.spark.sql.sources.StringEndsWith => true

        // Unsupported filter types that would break aggregation accuracy
        case filter if filter.getClass.getSimpleName.contains("RLike") =>
          logger.info(s"🔍 FILTER COMPATIBILITY: Regular expression filter not supported for aggregation: $filter")
          false
        case other =>
          logger.info(s"🔍 FILTER COMPATIBILITY: Unknown filter type, assuming supported: $other")
          true
      }

      // If filter type is supported, validate that filter fields are fast fields
      // For aggregate pushdown, ALL filters require fast field configuration to ensure correctness
      // This will throw IllegalArgumentException if validation fails
      if (isFilterTypeSupported) {
        validateFilterFieldsAreFast(filter)
      } else {
        throw new IllegalArgumentException(s"Filter type not supported for aggregation pushdown: $filter")
      }
    }

    true
  }

  /**
   * Get partition columns from the transaction log metadata.
   */
  private def getPartitionColumns(): Set[String] = {
    try {
      val metadata = transactionLog.getMetadata()
      metadata.partitionColumns.toSet
    } catch {
      case e: Exception =>
        logger.warn(s"🔍 PARTITION COLUMNS: Failed to get partition columns from transaction log: ${e.getMessage}")
        Set.empty
    }
  }

  /**
   * Get fast fields from the actual table schema/docMappingJson, not from configuration.
   * This reads the transaction log to determine which fields are actually configured as fast.
   */
  private def getActualFastFieldsFromSchema(): Set[String] = {
    try {
      logger.debug("🔍 SCHEMA FAST FIELD VALIDATION: Reading actual fast fields from transaction log")

      // Read existing files from transaction log to get docMappingJson
      val existingFiles = transactionLog.listFiles()
      val existingDocMapping = existingFiles
        .flatMap(_.docMappingJson)
        .headOption // Get the first available doc mapping

      if (existingDocMapping.isDefined) {
        logger.debug("🔍 SCHEMA FAST FIELD VALIDATION: Found doc mapping, parsing fast fields")

        // Parse the docMappingJson to extract fast field information
        import com.fasterxml.jackson.databind.JsonNode
        import com.tantivy4spark.util.JsonUtil
        import scala.jdk.CollectionConverters._

        val mappingJson = existingDocMapping.get
        val docMapping = JsonUtil.mapper.readTree(mappingJson)

        if (docMapping.isArray) {
          val fastFields = docMapping.asScala.flatMap { fieldNode =>
            val fieldName = Option(fieldNode.get("name")).map(_.asText())
            val isFast = Option(fieldNode.get("fast"))
              .map(_.asBoolean())
              .getOrElse(false)

            if (isFast && fieldName.isDefined) {
              logger.debug(s"🔍 SCHEMA FAST FIELD VALIDATION: Found fast field: ${fieldName.get}")
              Some(fieldName.get)
            } else {
              None
            }
          }.toSet

          logger.info(s"🔍 SCHEMA FAST FIELD VALIDATION: Actual fast fields from schema: ${fastFields.mkString(", ")}")
          fastFields
        } else {
          logger.warn("🔍 SCHEMA FAST FIELD VALIDATION: Doc mapping is not an array - unexpected format")
          Set.empty[String]
        }
      } else {
        logger.debug("🔍 SCHEMA FAST FIELD VALIDATION: No doc mapping found - likely new table, falling back to configuration-based validation")
        // Fall back to configuration-based validation for new tables
        val mergedConfig = broadcastConfig.value
        val fastFieldsStr = mergedConfig.get("spark.indextables.indexing.fastfields")
          .orElse(mergedConfig.get("spark.indextables.indexing.fastfields"))
          .getOrElse("")
        if (fastFieldsStr.nonEmpty) {
          fastFieldsStr.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        } else {
          Set.empty[String]
        }
      }
    } catch {
      case e: Exception =>
        logger.warn(s"🔍 SCHEMA FAST FIELD VALIDATION: Failed to read fast fields from schema: ${e.getMessage}")
        // Fall back to configuration-based validation
        val mergedConfig = broadcastConfig.value
        val fastFieldsStr = mergedConfig.get("spark.indextables.indexing.fastfields")
          .orElse(mergedConfig.get("spark.indextables.indexing.fastfields"))
          .getOrElse("")
        if (fastFieldsStr.nonEmpty) {
          fastFieldsStr.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        } else {
          Set.empty[String]
        }
    }
  }

  private def validateFilterFieldsAreFast(filter: org.apache.spark.sql.sources.Filter): Boolean = {
    // Get actual fast fields from the schema/docMappingJson
    val fastFields = getActualFastFieldsFromSchema()

    // Get partition columns - these don't need to be fast since they're handled by transaction log
    val partitionColumns = getPartitionColumns()

    // Extract field names from filter
    val filterFields = extractFieldNamesFromFilter(filter)

    // Exclude partition columns from fast field validation
    val nonPartitionFilterFields = filterFields -- partitionColumns

    logger.info(s"🔍 FILTER VALIDATION: All filter fields: ${filterFields.mkString(", ")}")
    logger.info(s"🔍 FILTER VALIDATION: Partition columns: ${partitionColumns.mkString(", ")}")
    logger.info(s"🔍 FILTER VALIDATION: Non-partition filter fields: ${nonPartitionFilterFields.mkString(", ")}")
    logger.info(s"🔍 FILTER VALIDATION: Fast fields from schema: ${fastFields.mkString(", ")}")
    println(s"🔍 FILTER VALIDATION DEBUG: filterFields=$filterFields, partitionColumns=$partitionColumns, nonPartitionFilterFields=$nonPartitionFilterFields, fastFields=$fastFields")

    // If all filter fields are partition columns, we don't need fast fields (transaction log optimization)
    if (nonPartitionFilterFields.isEmpty) {
      logger.info(s"🔍 FILTER VALIDATION: All filters are on partition columns - no fast fields required")
      return true
    }

    // Check if all non-partition filter fields are configured as fast fields
    val missingFastFields = nonPartitionFilterFields.filterNot(fastFields.contains)
    println(s"🔍 FILTER VALIDATION DEBUG: missingFastFields=$missingFastFields")

    if (missingFastFields.nonEmpty) {
      val columnList = missingFastFields.mkString("'", "', '", "'")
      val currentFastFields = if (fastFields.nonEmpty) fastFields.mkString("'", "', '", "'") else "none"

      logger.info(s"🔍 FILTER FAST FIELD VALIDATION: Missing fast fields for filter: $columnList")
      logger.info(s"🔍 FILTER FAST FIELD VALIDATION: Current fast fields from schema: $currentFastFields")
      logger.info(s"🔍 FILTER FAST FIELD VALIDATION: Filter rejected for aggregation pushdown: $filter")

      throw new IllegalArgumentException(
        s"""COUNT aggregation with filters requires fast field configuration.
           |
           |Missing fast fields for filter columns: $columnList
           |
           |The table schema shows these fields are not configured as fast fields.
           |With the new default behavior, numeric, string, and date fields should be fast by default.
           |
           |Current fast fields in schema: $currentFastFields
           |Required fast fields: ${(fastFields ++ missingFastFields).toSeq.distinct.mkString("'", "', '", "'")}
           |
           |Filter: $filter""".stripMargin
      )
    }

    true
  }

  /**
   * Extract field names from a Spark Filter.
   */
  private def extractFieldNamesFromFilter(filter: org.apache.spark.sql.sources.Filter): Set[String] = {
    filter match {
      case f: org.apache.spark.sql.sources.EqualTo => Set(f.attribute)
      case f: org.apache.spark.sql.sources.GreaterThan => Set(f.attribute)
      case f: org.apache.spark.sql.sources.LessThan => Set(f.attribute)
      case f: org.apache.spark.sql.sources.GreaterThanOrEqual => Set(f.attribute)
      case f: org.apache.spark.sql.sources.LessThanOrEqual => Set(f.attribute)
      case f: org.apache.spark.sql.sources.In => Set(f.attribute)
      case f: org.apache.spark.sql.sources.IsNull => Set(f.attribute)
      case f: org.apache.spark.sql.sources.IsNotNull => Set(f.attribute)
      case f: org.apache.spark.sql.sources.StringContains => Set(f.attribute)
      case f: org.apache.spark.sql.sources.StringStartsWith => Set(f.attribute)
      case f: org.apache.spark.sql.sources.StringEndsWith => Set(f.attribute)
      case f: org.apache.spark.sql.sources.And =>
        extractFieldNamesFromFilter(f.left) ++ extractFieldNamesFromFilter(f.right)
      case f: org.apache.spark.sql.sources.Or =>
        extractFieldNamesFromFilter(f.left) ++ extractFieldNamesFromFilter(f.right)
      case other =>
        logger.warn(s"🔍 FILTER FIELD EXTRACTION: Unknown filter type, cannot extract fields: $other")
        Set.empty[String]
    }
  }

  /**
   * Check if GROUP BY columns are supported for pushdown.
   */
  private def areGroupByColumnsSupported(groupByColumns: Array[String]): Boolean = {
    println(s"🔍 GROUP BY VALIDATION: Checking ${groupByColumns.length} columns: ${groupByColumns.mkString(", ")}")
    println(s"🔍 GROUP BY VALIDATION: Schema fields: ${schema.fields.map(_.name).mkString(", ")}")

    groupByColumns.forall { columnName =>
      println(s"🔍 GROUP BY VALIDATION: Checking column '$columnName'")
      // Check if the column exists in the schema
      val fieldExists = schema.fields.exists(_.name == columnName)
      println(s"🔍 GROUP BY VALIDATION: Field '$columnName' exists: $fieldExists")
      if (!fieldExists) {
        println(s"🔍 GROUP BY VALIDATION: Field '$columnName' not found in schema")
        logger.info(s"🔍 GROUP BY VALIDATION: Field '$columnName' not found in schema")
        return false
      }

      // For GROUP BY, we need fields that can be used for terms aggregation
      // String fields work well for grouping
      schema.fields.find(_.name == columnName) match {
        case Some(field) =>
          import org.apache.spark.sql.types._
          // ALL GROUP BY fields must be fast fields for tantivy4java TermsAggregation
          val fastFields = getActualFastFieldsFromSchema()
          val isFast = fastFields.contains(columnName)

          field.dataType match {
            case StringType =>
              if (isFast) {
                logger.info(s"🔍 GROUP BY VALIDATION: Fast string field '$columnName' is supported for GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: Fast string field '$columnName' is supported for GROUP BY")
                true
              } else {
                logger.info(s"🔍 GROUP BY VALIDATION: String field '$columnName' must be fast field for tantivy4java GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: String field '$columnName' must be fast field for tantivy4java GROUP BY")
                false
              }
            case IntegerType | LongType =>
              if (isFast) {
                logger.info(s"🔍 GROUP BY VALIDATION: Fast numeric field '$columnName' is supported for GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: Fast numeric field '$columnName' is supported for GROUP BY")
                true
              } else {
                logger.info(s"🔍 GROUP BY VALIDATION: Numeric field '$columnName' must be fast field for efficient GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: Numeric field '$columnName' must be fast field for efficient GROUP BY")
                false
              }
            case DateType | TimestampType =>
              if (isFast) {
                logger.info(s"🔍 GROUP BY VALIDATION: Fast date/timestamp field '$columnName' is supported for GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: Fast date/timestamp field '$columnName' is supported for GROUP BY")
                true
              } else {
                logger.info(s"🔍 GROUP BY VALIDATION: Date/timestamp field '$columnName' must be fast field for GROUP BY")
                println(s"🔍 GROUP BY VALIDATION: Date/timestamp field '$columnName' must be fast field for GROUP BY")
                false
              }
            case _ =>
              logger.info(s"🔍 GROUP BY VALIDATION: Field type ${field.dataType} not supported for GROUP BY")
              println(s"🔍 GROUP BY VALIDATION: Field type ${field.dataType} not supported for GROUP BY")
              false
          }
        case None => false
      }
    }
  }

  /**
   * Check if the current aggregation is compatible with GROUP BY.
   */
  private def isAggregationCompatibleWithGroupBy(aggregation: Aggregation): Boolean = {
    import org.apache.spark.sql.connector.expressions.aggregate._
    println(s"🔍 GROUP BY COMPATIBILITY: Checking ${aggregation.aggregateExpressions.length} aggregate expressions")

    // Check each aggregate expression - for GROUP BY, ALL aggregated fields must be fast fields
    val result = aggregation.aggregateExpressions.forall { expr =>
      val isCompatible = expr match {
        case _: Count =>
          println(s"🔍 GROUP BY COMPATIBILITY: COUNT is compatible with GROUP BY (no field required)")
          true
        case _: CountStar =>
          println(s"🔍 GROUP BY COMPATIBILITY: COUNT(*) is compatible with GROUP BY (no field required)")
          true
        case sum: Sum =>
          val fieldName = getFieldName(sum.column)
          val isFast = isNumericFastField(fieldName)
          if (isFast) {
            println(s"🔍 GROUP BY COMPATIBILITY: SUM($fieldName) is compatible with GROUP BY (fast field)")
            true
          } else {
            println(s"🔍 GROUP BY COMPATIBILITY: SUM($fieldName) requires fast field for GROUP BY")
            logger.info(s"🔍 GROUP BY COMPATIBILITY: SUM($fieldName) requires fast field for GROUP BY")
            false
          }
        case avg: Avg =>
          val fieldName = getFieldName(avg.column)
          val isFast = isNumericFastField(fieldName)
          if (isFast) {
            println(s"🔍 GROUP BY COMPATIBILITY: AVG($fieldName) is compatible with GROUP BY (fast field)")
            true
          } else {
            println(s"🔍 GROUP BY COMPATIBILITY: AVG($fieldName) requires fast field for GROUP BY")
            logger.info(s"🔍 GROUP BY COMPATIBILITY: AVG($fieldName) requires fast field for GROUP BY")
            false
          }
        case min: Min =>
          val fieldName = getFieldName(min.column)
          val isFast = isNumericFastField(fieldName)
          if (isFast) {
            println(s"🔍 GROUP BY COMPATIBILITY: MIN($fieldName) is compatible with GROUP BY (fast field)")
            true
          } else {
            println(s"🔍 GROUP BY COMPATIBILITY: MIN($fieldName) requires fast field for GROUP BY")
            logger.info(s"🔍 GROUP BY COMPATIBILITY: MIN($fieldName) requires fast field for GROUP BY")
            false
          }
        case max: Max =>
          val fieldName = getFieldName(max.column)
          val isFast = isNumericFastField(fieldName)
          if (isFast) {
            println(s"🔍 GROUP BY COMPATIBILITY: MAX($fieldName) is compatible with GROUP BY (fast field)")
            true
          } else {
            println(s"🔍 GROUP BY COMPATIBILITY: MAX($fieldName) requires fast field for GROUP BY")
            logger.info(s"🔍 GROUP BY COMPATIBILITY: MAX($fieldName) requires fast field for GROUP BY")
            false
          }
        case other =>
          println(s"🔍 GROUP BY COMPATIBILITY: Unsupported aggregation type with GROUP BY: ${other.getClass.getSimpleName}")
          logger.info(s"🔍 GROUP BY COMPATIBILITY: Unsupported aggregation type with GROUP BY: ${other.getClass.getSimpleName}")
          false
      }
      println(s"🔍 GROUP BY COMPATIBILITY: Expression $expr compatible: $isCompatible")
      isCompatible
    }
    println(s"🔍 GROUP BY COMPATIBILITY: Overall compatibility: $result")
    result
  }

  /**
   * Check if a filter is a partition filter.
   */
  private def isPartitionFilter(filter: org.apache.spark.sql.sources.Filter): Boolean = {
    val partitionColumns = getPartitionColumns()
    val referencedColumns = getFilterReferencedColumns(filter)
    referencedColumns.nonEmpty && referencedColumns.forall(partitionColumns.contains)
  }

  /**
   * Get columns referenced by a filter.
   */
  private def getFilterReferencedColumns(filter: org.apache.spark.sql.sources.Filter): Set[String] = {
    import org.apache.spark.sql.sources._
    filter match {
      case EqualTo(attribute, _) => Set(attribute)
      case EqualNullSafe(attribute, _) => Set(attribute)
      case GreaterThan(attribute, _) => Set(attribute)
      case GreaterThanOrEqual(attribute, _) => Set(attribute)
      case LessThan(attribute, _) => Set(attribute)
      case LessThanOrEqual(attribute, _) => Set(attribute)
      case In(attribute, _) => Set(attribute)
      case IsNull(attribute) => Set(attribute)
      case IsNotNull(attribute) => Set(attribute)
      case StringStartsWith(attribute, _) => Set(attribute)
      case StringEndsWith(attribute, _) => Set(attribute)
      case StringContains(attribute, _) => Set(attribute)
      case And(left, right) => getFilterReferencedColumns(left) ++ getFilterReferencedColumns(right)
      case Or(left, right) => getFilterReferencedColumns(left) ++ getFilterReferencedColumns(right)
      case Not(child) => getFilterReferencedColumns(child)
      case _ => Set.empty[String]
    }
  }

  /**
   * Validate GROUP BY columns and throw a descriptive exception if validation fails.
   */
  private def validateGroupByColumnsOrThrow(groupByColumns: Array[String]): Unit = {
    val missingFastFields = scala.collection.mutable.ArrayBuffer[String]()

    // Use broadcast config instead of options for merged configuration
    val mergedConfig = broadcastConfig.value
    val fastFieldsStr = mergedConfig.get("spark.indextables.indexing.fastfields")
      .orElse(mergedConfig.get("spark.indextables.indexing.fastfields"))
      .getOrElse("")

    val fastFields = if (fastFieldsStr.nonEmpty) {
      fastFieldsStr.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    } else {
      Set.empty[String]
    }


    groupByColumns.foreach { columnName =>
      // Check if the column exists in the schema
      schema.fields.find(_.name == columnName) match {
        case Some(field) =>
          import org.apache.spark.sql.types._
          // Check if field is configured as fast field
          if (!fastFields.contains(columnName)) {
            missingFastFields += columnName
          }
        case None =>
          throw new IllegalArgumentException(
            s"GROUP BY column '$columnName' not found in schema. Available columns: ${schema.fields.map(_.name).mkString(", ")}"
          )
      }
    }

    if (missingFastFields.nonEmpty) {
      val columnList = missingFastFields.mkString("'", "', '", "'")
      val currentFastFields = if (fastFields.nonEmpty) fastFields.mkString("'", "', '", "'") else "none"

      throw new IllegalArgumentException(
        s"""GROUP BY requires fast field configuration for efficient aggregation.
           |
           |Missing fast fields for GROUP BY columns: $columnList
           |
           |To fix this issue, configure these columns as fast fields:
           |  .option("spark.indexfiles.indexing.fastfields", "${ (fastFields ++ missingFastFields).toSeq.distinct.mkString(",") }")
           |
           |Current fast fields: $currentFastFields
           |Required fast fields: ${(fastFields ++ missingFastFields).toSeq.distinct.mkString("'", "', '", "'")}""".stripMargin
      )
    }
  }

  /**
   * Validate aggregation compatibility with GROUP BY and throw a descriptive exception if validation fails.
   */
  private def validateAggregationCompatibilityOrThrow(aggregation: Aggregation): Unit = {
    import org.apache.spark.sql.connector.expressions.aggregate._
    val missingFastFields = scala.collection.mutable.ArrayBuffer[String]()

    // Use broadcast config instead of options for merged configuration
    val mergedConfig = broadcastConfig.value
    val fastFieldsStr = mergedConfig.get("spark.indextables.indexing.fastfields")
      .orElse(mergedConfig.get("spark.indextables.indexing.fastfields"))
      .getOrElse("")

    val fastFields = if (fastFieldsStr.nonEmpty) {
      fastFieldsStr.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    } else {
      Set.empty[String]
    }

    aggregation.aggregateExpressions.foreach { expr =>
      expr match {
        case _: Count | _: CountStar =>
          // COUNT and COUNT(*) don't require specific fast fields
        case sum: Sum =>
          val fieldName = getFieldName(sum.column)
          if (!isNumericFastField(fieldName)) {
            missingFastFields += fieldName
          }
        case avg: Avg =>
          val fieldName = getFieldName(avg.column)
          if (!isNumericFastField(fieldName)) {
            missingFastFields += fieldName
          }
        case min: Min =>
          val fieldName = getFieldName(min.column)
          if (!isNumericFastField(fieldName)) {
            missingFastFields += fieldName
          }
        case max: Max =>
          val fieldName = getFieldName(max.column)
          if (!isNumericFastField(fieldName)) {
            missingFastFields += fieldName
          }
        case other =>
          throw new UnsupportedOperationException(
            s"Aggregation function '${other.getClass.getSimpleName}' is not supported with GROUP BY operations"
          )
      }
    }

    if (missingFastFields.nonEmpty) {
      val columnList = missingFastFields.mkString("'", "', '", "'")
      val currentFastFields = if (fastFields.nonEmpty) fastFields.mkString("'", "', '", "'") else "none"
      val allRequiredFields = (fastFields ++ missingFastFields).toSeq.distinct

      throw new IllegalArgumentException(
        s"""GROUP BY with aggregation functions requires fast field configuration for efficient processing.
           |
           |Missing fast fields for aggregation columns: $columnList
           |
           |To fix this issue, configure these columns as fast fields:
           |  .option("spark.indexfiles.indexing.fastfields", "${allRequiredFields.mkString(",")}")
           |
           |Current fast fields: $currentFastFields
           |Required fast fields: ${allRequiredFields.mkString("'", "', '", "'")}""".stripMargin
      )
    }
  }
}

/**
 * Companion object for ScanBuilder to store IndexQuery information.
 * This provides a clean mechanism for V2IndexQueryExpressionRule to pass
 * IndexQuery expressions directly to the ScanBuilder without a global registry.
 */
object Tantivy4SparkScanBuilder {
  import scala.collection.concurrent

  // Thread-safe storage for IndexQuery expressions scoped by DataSource instance
  private val instanceIndexQueries = concurrent.TrieMap[String, scala.collection.mutable.Buffer[Any]]()

  /**
   * Store IndexQuery expressions for a specific DataSource instance.
   * The key should be unique per query execution to avoid conflicts.
   */
  def storeIndexQueries(instanceKey: String, indexQueries: Seq[Any]): Unit = {
    val buffer = instanceIndexQueries.getOrElseUpdate(instanceKey, scala.collection.mutable.Buffer.empty)
    buffer.clear()
    buffer ++= indexQueries
    println(s"🔍 ScanBuilder: Stored ${indexQueries.length} IndexQuery expressions for instance $instanceKey")
  }

  /**
   * Retrieve IndexQuery expressions for a specific DataSource instance.
   */
  def getIndexQueries(instanceKey: String): Seq[Any] = {
    val queries = instanceIndexQueries.getOrElse(instanceKey, scala.collection.mutable.Buffer.empty).toSeq
    println(s"🔍 ScanBuilder: Retrieved ${queries.length} IndexQuery expressions for instance $instanceKey")
    queries
  }

  /**
   * Clear IndexQuery expressions for a specific DataSource instance.
   */
  def clearIndexQueries(instanceKey: String): Unit = {
    instanceIndexQueries.remove(instanceKey)
    println(s"🔍 ScanBuilder: Cleared IndexQuery expressions for instance $instanceKey")
  }

  /**
   * Generate a unique instance key for a DataSource relation.
   */
  def generateInstanceKey(tablePath: String, executionId: Option[String]): String = {
    // Use a more deterministic key based only on table path to avoid timing issues
    val cleanPath = tablePath.replace('/', '_').replace('\\', '_')
    cleanPath // Remove execution ID dependency for now
  }
}
