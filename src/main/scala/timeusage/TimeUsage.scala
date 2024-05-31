package timeusage

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.log4j.{Level, Logger}

import scala.util.Properties.isWin

/** Main class */
object TimeUsage extends TimeUsageInterface {
    import org.apache.spark.sql.SparkSession
    import org.apache.spark.sql.functions._

    // Reduce Spark logging verbosity
    Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
    if (isWin) System.setProperty("hadoop.home.dir", System.getProperty("user.dir") + "\\winutils\\hadoop-3.3.1")

    val spark: SparkSession =
        SparkSession
            .builder()
            .appName("Time Usage")
            .master("local")
            .getOrCreate()

    // For implicit conversions like converting RDDs to DataFrames
    import spark.implicits._

    /** Main function */
    def main(args: Array[String]): Unit = {
        timeUsageByLifePeriod()
        spark.close()
    }

    def timeUsageByLifePeriod(): Unit = {
        val (columns, initDf) = read("src/main/resources/timeusage/atussum.csv")
        val (primaryNeedsColumns, workColumns, otherColumns) = classifiedColumns(columns)
        val summaryDf = timeUsageSummary(primaryNeedsColumns, workColumns, otherColumns, initDf)
        /** Méthode 1 */
        val finalDf = timeUsageGrouped(summaryDf)
        /** Méthode 2 */
//        val summaryDfTyped = timeUsageSummaryTyped(summaryDf)
//        val finalDf = timeUsageGroupedTyped(summaryDfTyped)
        finalDf.show()
    }

    /** @return The read DataFrame along with its column names. */
    def read(path: String): (List[String], DataFrame) = {
        val rawDf = spark.read
            .options(Map("header" -> "true", "inferSchema" -> "true"))
            .csv(path)
        val df = rawDf
            .select(
                rawDf.columns.map {
                    case c@"tucaseid" => col(c).cast(StringType)
                    case c => col(c).cast(DoubleType)
                }: _*
            )
        (df.schema.fields.map(_.name).toList, df)
    }

    /** @return An RDD Row compatible with the schema produced by `dfSchema`
     * @param line Raw fields
     */
    def row(line: List[String]): Row = {
        val convertedLine = line.map(l => if (l != "tucaseid") l.toDouble else l)
        Row.fromSeq(convertedLine)
    }

    /** @return The initial data frame columns partitioned in three groups: primary needs (sleeping, eating, etc.),
     *         work and other (leisure activities)
     *
     * @see https://www.kaggle.com/bls/american-time-use-survey
     *
     * The dataset contains the daily time (in minutes) people spent in various activities. For instance, the column
     * “t010101” contains the time spent sleeping, the column “t110101” contains the time spent eating and drinking, etc.
     *
     * This method groups related columns together:
     * 1. “primary needs” activities (sleeping, eating, etc.). These are the columns starting with “t01”, “t03”, “t11”,
     *    “t1801” and “t1803”.
     * 2. working activities. These are the columns starting with “t05” and “t1805”.
     * 3. other activities (leisure). These are the columns starting with “t02”, “t04”, “t06”, “t07”, “t08”, “t09”,
     *    “t10”, “t12”, “t13”, “t14”, “t15”, “t16” and “t18” (those which are not part of the previous groups only).
     */
    def classifiedColumns(columnNames: List[String]): (List[Column], List[Column], List[Column]) = {

        def isPrimaryNeeds(columnName: String): Boolean = {
            val prefixes = List("t01", "t03", "t11", "t1801", "t1803")
            prefixes.exists(columnName.startsWith)
        }

        def isWorking(columnName: String): Boolean = {
            val prefixes = List("t05", "t1805")
            prefixes.exists(columnName.startsWith)
        }

        def isOther(columnName: String): Boolean = {
            val prefixes = List("t02", "t04", "t06", "t07", "t08", "t09", "t10", "t12", "t13", "t14", "t15", "t16", "t18")
            prefixes.exists(columnName.startsWith) && !isPrimaryNeeds(columnName)
        }

        val primaryNeedsColumns = columnNames.filter(isPrimaryNeeds).map(col)
        val workingColumns = columnNames.filter(isWorking).map(col)
        val otherColumns = columnNames.filter(isOther).map(col)
        (primaryNeedsColumns, workingColumns, otherColumns)
    }

    /** @return a projection of the initial DataFrame such that all columns containing hours spent on primary needs
     *         are summed together in a single column (and same for work and leisure). The “teage” column is also
     *         projected to three values: "young", "active", "elder".
     *
     * @param primaryNeedsColumns List of columns containing time spent on “primary needs”
     * @param workColumns List of columns containing time spent working
     * @param otherColumns List of columns containing time spent doing other activities
     * @param df DataFrame whose schema matches the given column lists
     *
     * This methods builds an intermediate DataFrame that sums up all the columns of each group of activity into
     * a single column.
     *
     * The resulting DataFrame should have the following columns:
     * - working: value computed from the “telfs” column of the given DataFrame:
     *   - "working" if 1 <= telfs < 3
     *   - "not working" otherwise
     * - sex: value computed from the “tesex” column of the given DataFrame:
     *   - "male" if tesex = 1, "female" otherwise
     * - age: value computed from the “teage” column of the given DataFrame:
     *   - "young" if 15 <= teage <= 22,
     *   - "active" if 23 <= teage <= 55,
     *   - "elder" otherwise
     * - primaryNeeds: sum of all the `primaryNeedsColumns`, in hours
     * - work: sum of all the `workColumns`, in hours
     * - other: sum of all the `otherColumns`, in hours
     *
     * Finally, the resulting DataFrame should exclude people that are not employable (ie telfs = 5).
     *
     * Note that the initial DataFrame contains time in ''minutes''. You have to convert it into ''hours''.
     */
    def timeUsageSummary(
        primaryNeedsColumns: List[Column],
        workColumns: List[Column],
        otherColumns: List[Column],
        df: DataFrame
    ): DataFrame = {
        // Transform the data from the initial dataset into data that make
        // more sense for our use case
        // Hint: you can use the `when` and `otherwise` Spark functions
        // Hint: don’t forget to give your columns the expected name with the `as` method
        val workingStatusProjection: Column = when(col("telfs") < 3, "Travail")
            .otherwise("Non travail")
            .as("Travail")
        val sexProjection: Column = when(col("tesex") === 1, "Homme")
            .otherwise("Femme")
            .as("Sexe")
        val ageProjection: Column = when(col("teage").between(15, 22), "Jeune")
            .when(col("teage").between(23, 55), "Actif")
            .otherwise("Personne âgée")
            .as("Age")

        // Create columns that sum columns of the initial dataset
        // Hint: you want to create a complex column expression that sums other columns
        //       by using the `+` operator between them
        // Hint: don’t forget to convert the value to hours
        val primaryNeedsProjection: Column = (primaryNeedsColumns.reduce(_ + _) / 60).as("Total besoins primaires (en heures)")
        val workProjection: Column = round(workColumns.reduce(_ + _) / 60).as("Total travail (en heures)")
        val otherProjection: Column = round(otherColumns.reduce(_ + _) / 60).as("Total autres activités (en heures)")
        df
            .select(workingStatusProjection, sexProjection, ageProjection, primaryNeedsProjection, workProjection, otherProjection)
            .where($"telfs" <= 4) // Discard people who are not in labor force
    }

    /** @return the average daily time (in hours) spent in primary needs, working or leisure, grouped by the different
     *         ages of life (young, active or elder), sex and working status.
     * @param summed DataFrame returned by `timeUsageSumByClass`
     *
     * The resulting DataFrame should have the following columns:
     * - working: the “working” column of the `summed` DataFrame,
     * - sex: the “sex” column of the `summed` DataFrame,
     * - age: the “age” column of the `summed` DataFrame,
     * - primaryNeeds: the average value of the “primaryNeeds” columns of all the people that have the same working
     *   status, sex and age, rounded with a scale of 1 (using the `round` function),
     * - work: the average value of the “work” columns of all the people that have the same working status, sex
     *   and age, rounded with a scale of 1 (using the `round` function),
     * - other: the average value of the “other” columns all the people that have the same working status, sex and
     *   age, rounded with a scale of 1 (using the `round` function).
     *
     * Finally, the resulting DataFrame should be sorted by working status, sex and age.
     */
    def timeUsageGrouped(summed: DataFrame): DataFrame = {
        summed
            .groupBy("Travail", "Sexe", "Age")
            .agg(
                round(avg("Total besoins primaires (en heures)"), 1).as("Besoins primaires moyens (en heures)"),
                round(avg("Total travail (en heures)"), 1).as("Travail moyen (en heures)"),
                round(avg("Total autres activités (en heures)"), 1).as("Autres activités moyennes (en heures)")
            )
            .orderBy("Travail", "Sexe", "Age")
    }

    /**
     * @return Same as `timeUsageGrouped`, but using a plain SQL query instead
     * @param summed DataFrame returned by `timeUsageSumByClass`
     */
    def timeUsageGroupedSql(summed: DataFrame): DataFrame = {
        val viewName = s"summed"
        summed.createOrReplaceTempView(viewName)
        spark.sql(timeUsageGroupedSqlQuery(viewName))
    }

    /** @return SQL query equivalent to the transformation implemented in `timeUsageGrouped`
     * @param viewName Name of the SQL view to use
     */
    def timeUsageGroupedSqlQuery(viewName: String): String =
        s"""
           |SELECT Travail, Sexe, Age,
           |ROUND(AVG(`Total besoins primaires (en heures)`), 1) AS `Besoins primaires moyens (en heures)`,
           |ROUND(AVG(`Total travail (en heures)`), 1) AS `Travail moyen (en heures)`,
           |ROUND(AVG(`Total autres activités (en heures)`), 1) AS `Autres activités moyennes (en heures)`
           |FROM $viewName
           |GROUP BY Travail, Sexe, Age
           |ORDER BY Travail, Sexe, Age
           |""".stripMargin

    /**
     * @return A `Dataset[TimeUsageRow]` from the “untyped” `DataFrame`
     * @param timeUsageSummaryDf `DataFrame` returned by the `timeUsageSummary` method
     *
     * Hint: you should use the `getAs` method of `Row` to look up columns and
     * cast them at the same time.
     */
    def timeUsageSummaryTyped(timeUsageSummaryDf: DataFrame): Dataset[TimeUsageRow] =
        timeUsageSummaryDf.map {
            row =>
                TimeUsageRow(
                    row.getAs[String]("Travail"),
                    row.getAs[String]("Sexe"),
                    row.getAs[String]("Age"),
                    row.getAs[Double]("Total besoins primaires (en heures)"),
                    row.getAs[Double]("Total travail (en heures)"),
                    row.getAs[Double]("Total autres activités (en heures)")
                )
        }


    /**
     * @return Same as `timeUsageGrouped`, but using the typed API when possible
     * @param summed Dataset returned by the `timeUsageSummaryTyped` method
     *
     * Note that, though they have the same type (`Dataset[TimeUsageRow]`), the input
     * dataset contains one element per respondent, whereas the resulting dataset
     * contains one element per group (whose time spent on each activity kind has
     * been aggregated).
     *
     * Hint: you should use the `groupByKey` and `avg` methods.
     */
    def timeUsageGroupedTyped(summed: Dataset[TimeUsageRow]): Dataset[TimeUsageRow] = {
        summed
            .groupByKey(row => (row.working
                , row.sex
                , row.age))
            .agg(
                round(avg($"primaryNeeds"), 1).as("Besoin primaire moyen (en heures)").as[Double],
                round(avg($"work"), 1).as[Double],
                round(avg($"other"), 1).as[Double]
            )
            .map {
                case ((working, sex, age), primaryNeeds, work, other) =>
                    TimeUsageRow(working, sex, age, primaryNeeds, work, other)
            }


    }
}

/**
 * Models a row of the summarized data set
 * @param working Working status (either "working" or "not working")
 * @param sex Sex (either "male" or "female")
 * @param age Age (either "young", "active" or "elder")
 * @param primaryNeeds Number of daily hours spent on primary needs
 * @param work Number of daily hours spent on work
 * @param other Number of daily hours spent on other activities
 */
case class TimeUsageRow(
    working: String,
    sex: String,
    age: String,
    primaryNeeds: Double,
    work: Double,
    other: Double
)
