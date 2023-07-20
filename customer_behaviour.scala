package com.customer.practice
import org.apache.log4j.Logger
import org.apache.spark.sql
import org.apache.spark.sql.{Column, SparkSession, functions}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}

object customer_behaviour {
  @transient lazy val logger: Logger = Logger.getLogger(getClass.getName)

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .master("local[3]")
      .appName("Behaviour Analysis")
      .config("spark.cassandra.connection.host", "localhost")
      .config("spark.cassandra.connection.port", "9042")
      .config("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
      .config("spark.sql.catalog.lh", "com.datastax.spark.connector.datasource.CassandraCatalog")
      .config("spark.sql.shuffle.paritions", 3)
      .config("stopGracefullyOnShutdown", true)
      .getOrCreate()

    val click = spark.read.option("header", true)
      .format("csv")
      .option("path", "input/click.csv")
      .option("maxFilesPerTrigger", 1)
      .load()

    val customer = spark.read.option("header", true)
      .format("csv")
      .option("path", "input/customer.csv")
      .option("maxFilesPerTrigger", 1)
      .load()

    val purchase = spark.read.option("header", true)
      .format("csv")
      .option("path", "input/purchase.csv")
      .option("maxFilesPerTrigger", 1)
      .load()

    val click_new = click.select(split(col("userID,timestamp,page"), ",").getItem(0).as("userID"),
      split(col("userID,timestamp,page"), ",").getItem(1).as("timestamp"),
      split(col("userID,timestamp,page"), ",").getItem(2).as("page"))
    click_new.show()

    val customer_new = customer.select(split(col("userID,name,email"), ",").getItem(0).as("userID"),
      split(col("userID,name,email"), ",").getItem(1).as("name"),
      split(col("userID,name,email"), ",").getItem(2).as("email"))
    customer.show()

    val purchase_new = purchase.select(split(col("userID,timestamp,amount"), ",").getItem(0).as("userID"),
      split(col("userID,timestamp,amount"), ",").getItem(1).as("timestamp"),
      split(col("userID,timestamp,amount"), ",").getItem(2).as("amount"))
    purchase.show()


    customer_new.createOrReplaceTempView("table1_view")
    purchase_new.createOrReplaceTempView("table2_view")

    val join_query =""" SELECT t1.userID, t1.name, t1.email, t2.timestamp, t2.amount FROM table1_view t1 JOIN table2_view t2 ON t1.userID = t2.userID """

    val join_result = spark.sql(join_query)
    join_result.show()

    join_result.createOrReplaceTempView("user_data")

    val query =
      """
            SELECT userID, UPPER(name) as name, email,timestamp,amount
            FROM user_data
        """


    val result = spark.sql(query)
    result.show()

    val dfWithSeparation = result.withColumn("date", functions.to_date(functions.col("timestamp")))
      .withColumn("month", functions.month(functions.col("timestamp")))
      .withColumn("year", functions.year(functions.col("timestamp")))
      .withColumn("time", functions.date_format(functions.col("timestamp"), "HH:mm:ss"))
      .withColumn("day_of_week", functions.date_format(functions.col("timestamp"), "EEEE")) // EEEE format for day name
      .drop("timestamp")


    dfWithSeparation.show()

    dfWithSeparation.createOrReplaceTempView("new")

    val query2 =
      """
        SELECT userID,
                     name,
                     email,
                     amount,
                     date,
                     month,
                     year,
                     time,
                     day_of_week,
                     CASE
                       WHEN amount <= 50 THEN 'Low'
                       WHEN amount > 50 AND amount <= 100 THEN 'Medium'
                       WHEN amount > 100 AND amount <= 200 THEN 'High'
                       ELSE 'Very High'
                     END AS category
              FROM new
      """

    val result2 = spark.sql(query2)
    result2.show()

    result2.createOrReplaceTempView("new1")

    // Extract domain using SPLIT function
    val query3 =
      """
            SELECT userID,
                   name,
                   email,
                   amount,
                   date,
                   month,
                   year,
                   time,
                   day_of_week,
                   category,
                   SPLIT(email, '@')[1] AS domain
            FROM new1
          """

    val result3 = spark.sql(query3)
    result3.show()


    result3.createOrReplaceTempView("new4")

    // Perform data quality checks using Spark SQL
    val query4 =
      """
           SELECT
             COUNT(*) AS total_rows,
             COUNT(userID) AS non_null_userID_count,
             COUNT(name) AS non_null_name_count,
             COUNT(email) AS non_null_email_count,
             COUNT(amount) AS non_null_amount_count,
             SUM(CASE WHEN userID IS NULL THEN 1 ELSE 0 END) AS null_userID_count,
             SUM(CASE WHEN name IS NULL THEN 1 ELSE 0 END) AS null_name_count,
             SUM(CASE WHEN email IS NULL THEN 1 ELSE 0 END) AS null_email_count,
             SUM(CASE WHEN amount IS NULL THEN 1 ELSE 0 END) AS null_amount_count,
             SUM(CASE WHEN email NOT LIKE '%@%' THEN 1 ELSE 0 END) AS invalid_email_count
           FROM new4
         """
    val clickDF = click_new.withColumnRenamed("userID", "userid")
    val result4 = spark.sql(query4)
    result4.show()


    click_new.createOrReplaceTempView("new5")
    val query5 =
      """
           SELECT
             COUNT(*) AS total_rows,
             COUNT(userID) AS non_null_userID_count,
             COUNT(timestamp) AS non_null_timestamp_count,
             COUNT(page) AS non_null_page_count,
             SUM(CASE WHEN userID IS NULL THEN 1 ELSE 0 END) AS null_userID_count,
             SUM(CASE WHEN timestamp IS NULL THEN 1 ELSE 0 END) AS null_timestamp_count,
             SUM(CASE WHEN page IS NULL THEN 1 ELSE 0 END) AS null_page_count
           FROM new5
         """

    val result5 = spark.sql(query5)
    result5.show()

    val result6 = result3.withColumnRenamed("userID", "userid")


    result6.write
      .format("org.apache.spark.sql.cassandra")
      .option("keyspace", "behaviour")
      .option("table", "customer")
      .mode("append")
      .save()

    clickDF.write
      .format("org.apache.spark.sql.cassandra")
      .option("keyspace", "behaviour")
      .option("table", "click")
      .mode("append")
      .save()


  }

}
