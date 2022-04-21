package spark

import com.typesafe.config.ConfigFactory
import org.apache.spark.ml.classification.{LogisticRegression, RandomForestClassifier}
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{StandardScaler, VectorAssembler}
import org.apache.spark.sql.functions.{col, expr, when}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Encoders, SparkSession}
import play.api.libs.json._
import play.api.{Configuration, Logging}

import java.io.File
import scala.util.Try

object FitModel extends Logging {

  val config: Configuration = Configuration(
    ConfigFactory.parseFile(
      new File(getClass.getResource("/application.conf").getPath)
    )
  )

  // constants - model names
  val ModelName_LR = "Logistic Regression"
  val ModelName_RF = "Random Forest classifier"

  def data(spark: SparkSession, useCsv: Boolean = false): Option[DataFrame] =
    if (useCsv || config.get[Boolean]("spark.useCsv")) {
      loadCsv(config.get[String]("spark.csvPath"), spark)
        .flatMap(preprocessing(_))
        .toOption
    } else {
      DataUtils.loadDataFromFolder(config.get[String]("spark.h5FolderPath"), spark)
        .flatMap(preprocessing(_))
        .toOption
    }

  def getSchema(isTrainData: Boolean = true): StructType = StructType(
    // song_hotness is the label for training data
    (if (isTrainData) List(StructField("song_hotness", DoubleType)) else Nil) ++
      List(
        // metadata
        StructField("artist_familiarity", DoubleType),
        StructField("artist_hotttnesss", DoubleType),
        StructField("artist_id", StringType),
        StructField("artist_latitude", DoubleType),
        StructField("artist_location", StringType),
        StructField("artist_longitude", DoubleType),
        StructField("artist_name", StringType),
        StructField("title", StringType),
        // analysis
        StructField("danceability", DoubleType),
        StructField("duration", DoubleType),
        StructField("end_of_fade_in", DoubleType),
        StructField("energy", DoubleType),
        StructField("key", IntegerType),
        StructField("key_confidence", DoubleType),
        StructField("loudness", DoubleType),
        StructField("mode", IntegerType),
        StructField("mode_confidence", DoubleType),
        StructField("start_of_fade_out", DoubleType),
        StructField("tempo", DoubleType),
        StructField("time_signature", IntegerType),
        StructField("time_signature_confidence", DoubleType),
        // metadata arrays
        // TODO - convert to array type?
        StructField("artist_terms", StringType),
        StructField("artist_terms_freq", StringType),
        StructField("artist_terms_weight", StringType),
        // musicbrainz
        StructField("year", IntegerType)
  ))

  // TODO - This can be removed after the pipeline is setting up
  def loadCsv(filepath: String, spark: SparkSession): Try[DataFrame] = Try {
    spark.read
      .option("delimiter", ",")
      .schema(getSchema(true))
      .csv(filepath)
  }

  def dfFromJson(json: JsValue, spark: SparkSession): DataFrame = {
    spark.read
      .schema(getSchema(isTrainData = false))
      .json(
        spark.createDataset(List(json.toString()))(Encoders.STRING)
      )
  }

  def preprocessing(df: DataFrame, isTrainData: Boolean = true): Try[DataFrame] = Try {
    // drop songs before 1920 and those with nan values
    val df1 = df.filter(df("year") > 1920)
      .na.drop(List("artist_latitude", "artist_longitude"))
      // TODO - drop these two columns?
      // .drop("energy", "danceability")
      .withColumn("year", df("year") - 1920)

    val df2 = if (isTrainData) {
      val avgHotness = df1.select(expr("AVG(song_hotness)"))
        .collect().head.getDouble(0)

      // set training label & shift years
      df1.withColumn("label", when(df1("song_hotness") >= avgHotness, 1).otherwise(0))
    } else df1

    // set up feature vector assembler and transform df2
    val featuresDf = new VectorAssembler()
      .setInputCols(df2.dtypes
        .filter(x => !List("song_hotness", "label").contains(x._1) && List("DoubleType", "IntegerType").contains(x._2))
        .map(_._1)
      )
      .setOutputCol("raw_features")
      .transform(df2)

    // TODO - this scaler should be bundled with ML model, can use spark pipeline
    // scaled_feature_df
    new StandardScaler()
      .setInputCol("raw_features")
      .setOutputCol("features") // MUST set here "features", Model will find this col by specific name to train
      .fit(featuresDf)
      .transform(featuresDf)
  }


  def fit(df: DataFrame,
          modelName: String,
          evaluate: Boolean = false
  ): Try[Any] = Try {
    val dataSplit = df.randomSplit(Array(0.8, 0.2), seed = 11L)
    val trainSet = dataSplit(0).cache()

    val model = modelName match {
      case ModelName_LR => new LogisticRegression()
      case ModelName_RF => new RandomForestClassifier()
      case _ => throw new Exception("Invalid model name")
    }

    logger.info(s"Fitting with $modelName...")
    val trainedModel = model.fit(trainSet)
    logger.info(s"Fitting complete! [$modelName]")

    // evaluate model performance
    if (evaluate) {
      val testSet = dataSplit(1)
      val trainPrediction = trainedModel.transform(trainSet)
      val testPrediction = trainedModel.transform(testSet)

      logger.info("Accuracy on train set: " + evaluatePrediction(trainPrediction))
      logger.info("Accuracy on test set: " + evaluatePrediction(testPrediction))
    }

    trainedModel
  }


  def evaluatePrediction(prediction: DataFrame): Double = {
    val evaluator = new BinaryClassificationEvaluator()
      .setLabelCol("label")
      .setRawPredictionCol("rawPrediction")
      .setMetricName("areaUnderROC")

    evaluator.evaluate(prediction)
  }
}
