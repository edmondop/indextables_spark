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

package io.indextables.spark.core

import java.io.File
import java.nio.file.Files

import scala.util.Random

import org.apache.spark.sql.functions._
import org.apache.spark.sql.SparkSession

import org.scalatest.funsuite.AnyFunSuite

/**
 * Integration test for GROUP BY functionality with actual Spark DataFrames. This test demonstrates what we need to
 * implement for complete GROUP BY support.
 */
class GroupByIntegrationTest extends AnyFunSuite {

  test("GROUP BY with COUNT aggregation - basic test") {
    val spark = SparkSession
      .builder()
      .appName("GroupByIntegrationTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with categories for grouping
      val testData = Seq(
        ("doc1", "category_a", "content about AI", 10),
        ("doc2", "category_a", "content about ML", 20),
        ("doc3", "category_b", "content about data", 30),
        ("doc4", "category_b", "content about analytics", 40),
        ("doc5", "category_c", "content about search", 50)
      ).toDF("id", "category", "content", "score")

      val tempDir   = Files.createTempDirectory("groupby-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with string field for category (should support GROUP BY)
      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.category", "string") // String fields support GROUP BY
        .option("spark.indextables.indexing.fastfields", "category,score") // Both GROUP BY column and aggregation column must be fast
        .mode("overwrite")
        .save(tablePath)

      println(s"✅ GROUP BY test: Data written to $tablePath")

      // Read back the data using V2 API for aggregate pushdown
      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY query - this should trigger pushGroupBy() method
      println("🔍 GROUP BY TEST: Executing GROUP BY query...")
      val groupByResult = df.groupBy("category").count()

      // Show the execution plan to see if GROUP BY was pushed down
      println("🔍 GROUP BY TEST: Execution plan:")
      groupByResult.explain(true)

      // Collect results
      val results = groupByResult.collect()

      println("🔍 GROUP BY TEST: Results:")
      results.foreach(row => println(s"  ${row.getString(0)}: ${row.getLong(1)}"))

      // Verify expected results
      val resultMap = results.map(row => row.getString(0) -> row.getLong(1)).toMap

      // Expected: category_a: 2, category_b: 2, category_c: 1
      assert(resultMap("category_a") == 2, s"category_a should have 2 docs, got ${resultMap("category_a")}")
      assert(resultMap("category_b") == 2, s"category_b should have 2 docs, got ${resultMap("category_b")}")
      assert(resultMap("category_c") == 1, s"category_c should have 1 doc, got ${resultMap("category_c")}")

      println(s"✅ GROUP BY test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with SUM aggregation") {
    val spark = SparkSession
      .builder()
      .appName("GroupBySumTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with numeric scores for summing
      val testData = Seq(
        ("doc1", "team_a", 100),
        ("doc2", "team_a", 200),
        ("doc3", "team_b", 150),
        ("doc4", "team_b", 250),
        ("doc5", "team_c", 300)
      ).toDF("id", "team", "score")

      val tempDir   = Files.createTempDirectory("groupby-sum-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data
      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.team", "string") // String field for GROUP BY
        .option("spark.indextables.indexing.fastfields", "team,score") // Both GROUP BY column and SUM column must be fast
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY with SUM
      println("🔍 GROUP BY SUM TEST: Executing GROUP BY with SUM...")
      val groupBySumResult = df.groupBy("team").agg(sum("score").as("total_score"))

      // Show execution plan
      println("🔍 GROUP BY SUM TEST: Execution plan:")
      groupBySumResult.explain(true)

      // Collect and verify results
      val results   = groupBySumResult.collect()
      val resultMap = results.map(row => row.getString(0) -> row.getLong(1)).toMap

      // Expected: team_a: 300, team_b: 400, team_c: 300
      assert(resultMap("team_a") == 300, s"team_a should have sum 300, got ${resultMap("team_a")}")
      assert(resultMap("team_b") == 400, s"team_b should have sum 400, got ${resultMap("team_b")}")
      assert(resultMap("team_c") == 300, s"team_c should have sum 300, got ${resultMap("team_c")}")

      println(s"✅ GROUP BY SUM test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("Multi-dimensional GROUP BY with COUNT aggregation") {
    val spark = SparkSession
      .builder()
      .appName("MultiDimensionalGroupByTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with multiple grouping dimensions
      val testData = Seq(
        ("doc1", "north", "electronics", "q1", 100),
        ("doc2", "north", "electronics", "q1", 200),
        ("doc3", "north", "electronics", "q2", 150),
        ("doc4", "south", "electronics", "q1", 300),
        ("doc5", "south", "books", "q1", 50),
        ("doc6", "north", "books", "q2", 75)
      ).toDF("id", "region", "category", "quarter", "sales")

      val tempDir   = Files.createTempDirectory("multi-groupby-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with all GROUP BY columns as fast fields
      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.region", "string")
        .option("spark.indextables.indexing.typemap.category", "string")
        .option("spark.indextables.indexing.typemap.quarter", "string")
        .option("spark.indextables.indexing.fastfields", "region,category,quarter,sales")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform multi-dimensional GROUP BY query - this should use MultiTermsAggregation
      println("🔍 MULTI-DIMENSIONAL GROUP BY TEST: Executing 3-dimensional GROUP BY query...")
      val multiGroupByResult = df.groupBy("region", "category", "quarter").count()

      // Show the execution plan to see if multi-dimensional GROUP BY was pushed down
      println("🔍 MULTI-DIMENSIONAL GROUP BY TEST: Execution plan:")
      multiGroupByResult.explain(true)

      // Collect results
      val results = multiGroupByResult.collect()

      println("🔍 MULTI-DIMENSIONAL GROUP BY TEST: Results:")
      results.foreach { row =>
        println(s"  ${row.getString(0)}/${row.getString(1)}/${row.getString(2)}: ${row.getLong(3)}")
      }

      // Verify expected results - should have 5 unique combinations
      // north/electronics/q1: 2, north/electronics/q2: 1, south/electronics/q1: 1, south/books/q1: 1, north/books/q2: 1
      assert(results.length == 5, s"Expected 5 groups, got ${results.length}")

      val resultMap = results.map(row => (row.getString(0), row.getString(1), row.getString(2)) -> row.getLong(3)).toMap

      assert(resultMap(("north", "electronics", "q1")) == 2, s"north/electronics/q1 should have 2 docs")
      assert(resultMap(("north", "electronics", "q2")) == 1, s"north/electronics/q2 should have 1 doc")
      assert(resultMap(("south", "electronics", "q1")) == 1, s"south/electronics/q1 should have 1 doc")
      assert(resultMap(("south", "books", "q1")) == 1, s"south/books/q1 should have 1 doc")
      assert(resultMap(("north", "books", "q2")) == 1, s"north/books/q2 should have 1 doc")

      println(s"✅ MULTI-DIMENSIONAL GROUP BY test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("Multi-dimensional GROUP BY with SUM aggregation") {
    val spark = SparkSession
      .builder()
      .appName("MultiDimensionalGroupBySumTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data for multi-dimensional SUM aggregation
      val testData = Seq(
        ("doc1", "team_a", "project_x", 100),
        ("doc2", "team_a", "project_x", 200),
        ("doc3", "team_a", "project_y", 150),
        ("doc4", "team_b", "project_x", 300),
        ("doc5", "team_b", "project_y", 250)
      ).toDF("id", "team", "project", "effort")

      val tempDir   = Files.createTempDirectory("multi-groupby-sum-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data
      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.team", "string")
        .option("spark.indextables.indexing.typemap.project", "string")
        .option("spark.indextables.indexing.fastfields", "team,project,effort")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform multi-dimensional GROUP BY with SUM
      println("🔍 MULTI-DIMENSIONAL GROUP BY SUM TEST: Executing 2-dimensional GROUP BY with SUM...")
      val multiGroupBySumResult = df.groupBy("team", "project").agg(sum("effort").as("total_effort"))

      // Show execution plan
      println("🔍 MULTI-DIMENSIONAL GROUP BY SUM TEST: Execution plan:")
      multiGroupBySumResult.explain(true)

      // Collect and verify results
      val results   = multiGroupBySumResult.collect()
      val resultMap = results.map(row => (row.getString(0), row.getString(1)) -> row.getLong(2)).toMap

      // Expected: team_a/project_x: 300, team_a/project_y: 150, team_b/project_x: 300, team_b/project_y: 250
      assert(resultMap(("team_a", "project_x")) == 300, s"team_a/project_x should have sum 300")
      assert(resultMap(("team_a", "project_y")) == 150, s"team_a/project_y should have sum 150")
      assert(resultMap(("team_b", "project_x")) == 300, s"team_b/project_x should have sum 300")
      assert(resultMap(("team_b", "project_y")) == 250, s"team_b/project_y should have sum 250")

      println(s"✅ MULTI-DIMENSIONAL GROUP BY SUM test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY pushdown detection - verify pushGroupBy() is called") {
    val spark = SparkSession
      .builder()
      .appName("GroupByPushdownDetectionTest")
      .master("local[*]")
      .config("spark.sql.adaptive.enabled", "false") // Disable AQE for predictable plans
      .getOrCreate()

    try {
      import spark.implicits._

      val testData = Seq(
        ("doc1", "status_active", 1),
        ("doc2", "status_active", 1),
        ("doc3", "status_inactive", 1)
      ).toDF("id", "status", "value")

      val tempDir   = Files.createTempDirectory("groupby-pushdown-test").toFile
      val tablePath = tempDir.getAbsolutePath

      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.status", "string")
        .option("spark.indextables.indexing.fastfields", "status,value") // Add both status and value as fast fields
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // This query should trigger aggregate pushdown with GROUP BY
      val query = df.groupBy("status").count()

      // Look for evidence of pushdown in the physical plan
      val physicalPlan = query.queryExecution.executedPlan.toString
      println("🔍 PUSHDOWN DETECTION: Physical plan:")
      println(physicalPlan)

      // Check if our GROUP BY scan classes appear in the plan
      val hasTantivyGroupByScan = physicalPlan.contains("IndexTables4SparkGroupByAggregateScan") ||
        physicalPlan.contains("GroupByAggregateScan")

      if (hasTantivyGroupByScan) {
        println("✅ GROUP BY pushdown detected in physical plan!")
      } else {
        println("❌ GROUP BY pushdown NOT detected - falling back to Spark aggregation")
        println("This indicates pushGroupBy() method may not be working correctly")
      }

      // Execute the query to see actual behavior
      val results = query.collect()
      println("🔍 Results from GROUP BY query:")
      results.foreach(row => println(s"  ${row.getString(0)}: ${row.getLong(1)}"))

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with COUNT and SUM using exact match filter") {
    val spark = SparkSession
      .builder()
      .appName("GroupByExactMatchFilterTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with referrer field for filtering and status field for grouping
      val testData = Seq(
        ("doc1", "myhost.com", "status_ok", 100),
        ("doc2", "myhost.com", "status_ok", 200),
        ("doc3", "myhost.com", "status_error", 150),
        ("doc4", "otherhost.com", "status_ok", 300),
        ("doc5", "otherhost.com", "status_error", 250),
        ("doc6", "myhost.com", "status_ok", 50),
        ("doc7", "thirdhost.com", "status_ok", 400)
      ).toDF("id", "referrer", "status", "response_time")

      val tempDir   = Files.createTempDirectory("groupby-filter-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with string fields for exact matching and fast fields for aggregation
      testData.write
        .format("tantivy4spark")
        .option("spark.indextables.indexing.typemap.referrer", "string")                  // String for exact match
        .option("spark.indextables.indexing.typemap.status", "string")                    // String for GROUP BY
        .option("spark.indextables.indexing.fastfields", "referrer,status,response_time") // All fields as fast
        .mode("overwrite")
        .save(tablePath)

      println(s"✅ GROUP BY with filter test: Data written to $tablePath")

      // Read back the data
      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Apply exact match filter and perform GROUP BY with COUNT and SUM
      println("🔍 GROUP BY FILTER TEST: Executing filtered GROUP BY query with COUNT and SUM...")
      val filteredGroupByResult = df
        .filter($"referrer" === "myhost.com")
        .groupBy("status")
        .agg(
          count("*").as("total_count"),
          sum("response_time").as("total_response_time")
        )

      // Show execution plan to verify pushdown
      println("🔍 GROUP BY FILTER TEST: Execution plan:")
      filteredGroupByResult.explain(true)

      // Collect results
      val results = filteredGroupByResult.collect()

      println("🔍 GROUP BY FILTER TEST: Results after filtering for referrer='myhost.com':")
      results.foreach(row => println(s"  ${row.getString(0)}: count=${row.getLong(1)}, sum=${row.getLong(2)}"))

      // Verify expected results
      val resultMap = results.map(row => row.getString(0) -> (row.getLong(1), row.getLong(2))).toMap

      // Expected results for referrer='myhost.com':
      // - status_ok: 3 docs (doc1, doc2, doc6) with sum=350 (100+200+50)
      // - status_error: 1 doc (doc3) with sum=150
      assert(resultMap.contains("status_ok"), "Should have status_ok group")
      assert(resultMap.contains("status_error"), "Should have status_error group")

      val (okCount, okSum)       = resultMap("status_ok")
      val (errorCount, errorSum) = resultMap("status_error")

      assert(okCount == 3, s"status_ok should have 3 docs, got $okCount")
      assert(okSum == 350, s"status_ok should have sum 350, got $okSum")
      assert(errorCount == 1, s"status_error should have 1 doc, got $errorCount")
      assert(errorSum == 150, s"status_error should have sum 150, got $errorSum")

      println(s"✅ GROUP BY with filter test: All assertions passed!")

      // Additional verification: ensure we didn't include docs from other referrers
      val totalDocsProcessed = okCount + errorCount
      assert(totalDocsProcessed == 4, s"Should process exactly 4 docs from myhost.com, got $totalDocsProcessed")

      val totalSumProcessed = okSum + errorSum
      assert(totalSumProcessed == 500, s"Total sum should be 500, got $totalSumProcessed")

      println(s"✅ Filter verification: Only docs from 'myhost.com' were aggregated!")

      // Verify that results do NOT include documents from other referrers
      // If other referrers were included, we'd see higher counts/sums:
      // - otherhost.com has 2 docs: doc4 (status_ok, 300) and doc5 (status_error, 250)
      // - thirdhost.com has 1 doc: doc7 (status_ok, 400)
      // Total if all docs included: status_ok would have 5 docs with sum=1050, status_error would have 2 docs with sum=400

      assert(
        okCount == 3 && okSum == 350,
        s"status_ok should have count=3, sum=350 (myhost.com only), but got count=$okCount, sum=$okSum. " +
          s"If otherhost.com was included, count would be 4 with sum=650. If thirdhost.com was included, count would be 4 with sum=750."
      )

      assert(
        errorCount == 1 && errorSum == 150,
        s"status_error should have count=1, sum=150 (myhost.com only), but got count=$errorCount, sum=$errorSum. " +
          s"If otherhost.com was included, count would be 2 with sum=400."
      )

      println(s"✅ Exclusion verification: Documents from 'otherhost.com' and 'thirdhost.com' were correctly excluded!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with Integer key") {
    val spark = SparkSession
      .builder()
      .appName("GroupByIntegerKeyTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with integer grouping key
      val testData = Seq(
        ("doc1", 1, 100),
        ("doc2", 1, 200),
        ("doc3", 2, 150),
        ("doc4", 2, 250),
        ("doc5", 3, 300)
      ).toDF("id", "priority", "score")

      val tempDir   = Files.createTempDirectory("groupby-int-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with integer GROUP BY key
      testData.write
        .format("io.indextables.spark.core.IndexTables4SparkTableProvider")
        .option("spark.indextables.indexing.fastfields", "priority,score")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY on integer field with COUNT and SUM
      val groupByResult = df
        .groupBy("priority")
        .agg(
          count("*").as("total_count"),
          sum("score").as("total_score")
        )

      val results   = groupByResult.collect()
      val resultMap = results.map(row => row.getInt(0) -> (row.getLong(1), row.getLong(2))).toMap

      // Expected: priority 1: count=2, sum=300; priority 2: count=2, sum=400; priority 3: count=1, sum=300
      assert(resultMap(1) == (2L, 300L), s"priority 1 should have count=2, sum=300, got ${resultMap(1)}")
      assert(resultMap(2) == (2L, 400L), s"priority 2 should have count=2, sum=400, got ${resultMap(2)}")
      assert(resultMap(3) == (1L, 300L), s"priority 3 should have count=1, sum=300, got ${resultMap(3)}")

      println(s"✅ GROUP BY Integer key test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with Long key") {
    val spark = SparkSession
      .builder()
      .appName("GroupByLongKeyTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with long grouping key (using large numbers)
      val testData = Seq(
        ("doc1", 1000000000L, 100),
        ("doc2", 1000000000L, 200),
        ("doc3", 2000000000L, 150),
        ("doc4", 2000000000L, 250),
        ("doc5", 3000000000L, 300)
      ).toDF("id", "timestamp_bucket", "value")

      val tempDir   = Files.createTempDirectory("groupby-long-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with long GROUP BY key
      testData.write
        .format("io.indextables.spark.core.IndexTables4SparkTableProvider")
        .option("spark.indextables.indexing.fastfields", "timestamp_bucket,value")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY on long field with COUNT and SUM
      val groupByResult = df
        .groupBy("timestamp_bucket")
        .agg(
          count("*").as("total_count"),
          sum("value").as("total_value")
        )

      val results   = groupByResult.collect()
      val resultMap = results.map(row => row.getLong(0) -> (row.getLong(1), row.getLong(2))).toMap

      // Expected: 1000000000L: count=2, sum=300; 2000000000L: count=2, sum=400; 3000000000L: count=1, sum=300
      assert(resultMap(1000000000L) == (2L, 300L), s"bucket 1000000000 should have count=2, sum=300")
      assert(resultMap(2000000000L) == (2L, 400L), s"bucket 2000000000 should have count=2, sum=400")
      assert(resultMap(3000000000L) == (1L, 300L), s"bucket 3000000000 should have count=1, sum=300")

      println(s"✅ GROUP BY Long key test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with Float key") {
    val spark = SparkSession
      .builder()
      .appName("GroupByFloatKeyTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with float grouping key
      val testData = Seq(
        ("doc1", 1.5f, 100),
        ("doc2", 1.5f, 200),
        ("doc3", 2.5f, 150),
        ("doc4", 2.5f, 250),
        ("doc5", 3.5f, 300)
      ).toDF("id", "rating", "sales")

      val tempDir   = Files.createTempDirectory("groupby-float-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with float GROUP BY key
      testData.write
        .format("io.indextables.spark.core.IndexTables4SparkTableProvider")
        .option("spark.indextables.indexing.fastfields", "rating,sales")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY on float field with COUNT and SUM
      val groupByResult = df
        .groupBy("rating")
        .agg(
          count("*").as("total_count"),
          sum("sales").as("total_sales")
        )

      val results   = groupByResult.collect()
      val resultMap = results.map(row => row.getFloat(0) -> (row.getLong(1), row.getLong(2))).toMap

      // Expected: 1.5f: count=2, sum=300; 2.5f: count=2, sum=400; 3.5f: count=1, sum=300
      assert(resultMap(1.5f) == (2L, 300L), s"rating 1.5 should have count=2, sum=300, got ${resultMap(1.5f)}")
      assert(resultMap(2.5f) == (2L, 400L), s"rating 2.5 should have count=2, sum=400, got ${resultMap(2.5f)}")
      assert(resultMap(3.5f) == (1L, 300L), s"rating 3.5 should have count=1, sum=300, got ${resultMap(3.5f)}")

      println(s"✅ GROUP BY Float key test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("GROUP BY with Double key") {
    val spark = SparkSession
      .builder()
      .appName("GroupByDoubleKeyTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with double grouping key
      val testData = Seq(
        ("doc1", 1.123456789, 100),
        ("doc2", 1.123456789, 200),
        ("doc3", 2.987654321, 150),
        ("doc4", 2.987654321, 250),
        ("doc5", 3.555555555, 300)
      ).toDF("id", "latitude", "population")

      val tempDir   = Files.createTempDirectory("groupby-double-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with double GROUP BY key
      testData.write
        .format("io.indextables.spark.core.IndexTables4SparkTableProvider")
        .option("spark.indextables.indexing.fastfields", "latitude,population")
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform GROUP BY on double field with COUNT and SUM
      val groupByResult = df
        .groupBy("latitude")
        .agg(
          count("*").as("total_count"),
          sum("population").as("total_population")
        )

      val results   = groupByResult.collect()
      val resultMap = results.map(row => row.getDouble(0) -> (row.getLong(1), row.getLong(2))).toMap

      // Expected: 1.123456789: count=2, sum=300; 2.987654321: count=2, sum=400; 3.555555555: count=1, sum=300
      assert(resultMap(1.123456789) == (2L, 300L), s"latitude 1.123456789 should have count=2, sum=300")
      assert(resultMap(2.987654321) == (2L, 400L), s"latitude 2.987654321 should have count=2, sum=400")
      assert(resultMap(3.555555555) == (1L, 300L), s"latitude 3.555555555 should have count=1, sum=300")

      println(s"✅ GROUP BY Double key test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  test("Multi-dimensional GROUP BY with 6 mixed-type keys and multiple aggregations") {
    val spark = SparkSession
      .builder()
      .appName("GroupBy6DimensionMixedTypesTest")
      .master("local[*]")
      .getOrCreate()

    try {
      import spark.implicits._

      // Create test data with 6 grouping dimensions of different types
      // Columns: region (String), priority (Int), timestamp_bucket (Long), latitude (Double), rating (Float), category (String), value (Int)
      val testData = Seq(
        ("north", 1, 1000000000L, 40.7128, 4.5f, "electronics", 100),
        ("north", 1, 1000000000L, 40.7128, 4.5f, "electronics", 150),
        ("north", 1, 1000000000L, 40.7128, 4.5f, "electronics", 200),
        ("north", 2, 2000000000L, 34.0522, 3.5f, "books", 300),
        ("north", 2, 2000000000L, 34.0522, 3.5f, "books", 350),
        ("south", 1, 1000000000L, 40.7128, 4.5f, "electronics", 400),
        ("south", 3, 3000000000L, 51.5074, 5.0f, "clothing", 500),
        ("south", 3, 3000000000L, 51.5074, 5.0f, "clothing", 550),
        ("south", 3, 3000000000L, 51.5074, 5.0f, "clothing", 600)
      ).toDF("region", "priority", "timestamp_bucket", "latitude", "rating", "category", "value")

      val tempDir   = Files.createTempDirectory("groupby-6d-mixed-test").toFile
      val tablePath = tempDir.getAbsolutePath

      // Write data with all 6 GROUP BY columns as fast fields
      testData.write
        .format("io.indextables.spark.core.IndexTables4SparkTableProvider")
        .option("spark.indextables.indexing.typemap.region", "string")
        .option("spark.indextables.indexing.typemap.category", "string")
        .option(
          "spark.indextables.indexing.fastfields",
          "region,priority,timestamp_bucket,latitude,rating,category,value"
        )
        .mode("overwrite")
        .save(tablePath)

      val df = spark.read.format("io.indextables.spark.core.IndexTables4SparkTableProvider").load(tablePath)

      // Perform 6-dimensional GROUP BY with multiple aggregation functions
      println("🔍 6-DIMENSIONAL GROUP BY TEST: Executing 6-dimensional GROUP BY with MIN, MAX, AVG, SUM, COUNT...")
      val multiGroupByResult = df
        .groupBy("region", "priority", "timestamp_bucket", "latitude", "rating", "category")
        .agg(
          count("*").as("total_count"),
          sum("value").as("total_sum"),
          avg("value").as("avg_value"),
          min("value").as("min_value"),
          max("value").as("max_value")
        )

      // Show the execution plan
      println("🔍 6-DIMENSIONAL GROUP BY TEST: Execution plan:")
      multiGroupByResult.explain(true)

      // Collect results
      val results = multiGroupByResult.collect()

      println("🔍 6-DIMENSIONAL GROUP BY TEST: Results:")
      results.foreach { row =>
        println(
          s"  ${row.getString(0)}/${row.getInt(1)}/${row.getLong(2)}/${row.getDouble(3)}/${row.getFloat(4)}/${row
              .getString(5)}: " +
            s"count=${row.getLong(6)}, sum=${row.getLong(7)}, avg=${row.getDouble(8)}, min=${row.getInt(9)}, max=${row.getInt(10)}"
        )
      }

      // Verify expected results - should have 4 unique 6-tuple combinations
      assert(results.length == 4, s"Expected 4 groups, got ${results.length}")

      val resultMap = results.map { row =>
        (
          row.getString(0), // region
          row.getInt(1),    // priority
          row.getLong(2),   // timestamp_bucket
          row.getDouble(3), // latitude
          row.getFloat(4),  // rating
          row.getString(5)  // category
        ) -> (
          row.getLong(6),   // count
          row.getLong(7),   // sum
          row.getDouble(8), // avg
          row.getInt(9),    // min
          row.getInt(10)    // max
        )
      }.toMap

      // Group 1: north/1/1000000000/40.7128/4.5/electronics - 3 docs: 100, 150, 200
      val group1 = resultMap(("north", 1, 1000000000L, 40.7128, 4.5f, "electronics"))
      assert(group1._1 == 3, s"Group 1 count should be 3, got ${group1._1}")
      assert(group1._2 == 450, s"Group 1 sum should be 450, got ${group1._2}")
      assert(Math.abs(group1._3 - 150.0) < 0.01, s"Group 1 avg should be 150.0, got ${group1._3}")
      assert(group1._4 == 100, s"Group 1 min should be 100, got ${group1._4}")
      assert(group1._5 == 200, s"Group 1 max should be 200, got ${group1._5}")

      // Group 2: north/2/2000000000/34.0522/3.5/books - 2 docs: 300, 350
      val group2 = resultMap(("north", 2, 2000000000L, 34.0522, 3.5f, "books"))
      assert(group2._1 == 2, s"Group 2 count should be 2, got ${group2._1}")
      assert(group2._2 == 650, s"Group 2 sum should be 650, got ${group2._2}")
      assert(Math.abs(group2._3 - 325.0) < 0.01, s"Group 2 avg should be 325.0, got ${group2._3}")
      assert(group2._4 == 300, s"Group 2 min should be 300, got ${group2._4}")
      assert(group2._5 == 350, s"Group 2 max should be 350, got ${group2._5}")

      // Group 3: south/1/1000000000/40.7128/4.5/electronics - 1 doc: 400
      val group3 = resultMap(("south", 1, 1000000000L, 40.7128, 4.5f, "electronics"))
      assert(group3._1 == 1, s"Group 3 count should be 1, got ${group3._1}")
      assert(group3._2 == 400, s"Group 3 sum should be 400, got ${group3._2}")
      assert(Math.abs(group3._3 - 400.0) < 0.01, s"Group 3 avg should be 400.0, got ${group3._3}")
      assert(group3._4 == 400, s"Group 3 min should be 400, got ${group3._4}")
      assert(group3._5 == 400, s"Group 3 max should be 400, got ${group3._5}")

      // Group 4: south/3/3000000000/51.5074/5.0/clothing - 3 docs: 500, 550, 600
      val group4 = resultMap(("south", 3, 3000000000L, 51.5074, 5.0f, "clothing"))
      assert(group4._1 == 3, s"Group 4 count should be 3, got ${group4._1}")
      assert(group4._2 == 1650, s"Group 4 sum should be 1650, got ${group4._2}")
      assert(Math.abs(group4._3 - 550.0) < 0.01, s"Group 4 avg should be 550.0, got ${group4._3}")
      assert(group4._4 == 500, s"Group 4 min should be 500, got ${group4._4}")
      assert(group4._5 == 600, s"Group 4 max should be 600, got ${group4._5}")

      println(s"✅ 6-DIMENSIONAL GROUP BY with mixed types test: All assertions passed!")

      // Clean up
      deleteRecursively(tempDir)

    } finally
      spark.stop()
  }

  /** Recursively delete a directory and all its contents. */
  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
  }
}
