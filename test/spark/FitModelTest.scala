package spark

import org.apache.spark.ml.classification.{LogisticRegression, RandomForestClassifier}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{count, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow
import play.api.libs.json.{JsValue, Json}

import scala.util.Success

class FitModelTest extends AnyFlatSpec with Matchers{

  val SAMPLE_SONGS_FILEPATH = "/sample_songs.csv"

  val spark: SparkSession = SparkSession
    .builder()
    .appName("test")
    .master("local[*]")
    .getOrCreate()

  val json: JsValue = Json.parse{
    val file = scala.io.Source.fromFile(getClass.getResource("/sample.json").getPath)
    val js = file.mkString
    file.close()
    js
  }


  behavior of "ML Pipeline"

  it should "successfully preprocess dataframe" taggedAs Slow in {

    val filepath = getClass.getResource("/sample_songs.csv").getPath

    val try_processed_df = for(df <- FitModel.loadCsv(filepath, spark);
                               pdf <- FitModel.preprocessing(df)) yield pdf

    try_processed_df should matchPattern {
      case Success(_) =>
    }

    val processed_df = try_processed_df.get

    val popular_songs_num = processed_df.select(count(when(processed_df("label")===1, 1))).first().getLong(0)
    popular_songs_num shouldBe 454

    val features_num = processed_df.select("features").take(1)(0)(0).toString.split(",").length
    features_num shouldBe 18
  }

  it should "successfully train and predict" taggedAs Slow in {
    val filepath = getClass.getResource(SAMPLE_SONGS_FILEPATH).getPath

    val try_models = for (
      raw_df <- FitModel.loadCsv(filepath, spark);
      processed_df <- FitModel.preprocessing(raw_df)
    ) yield (
      FitModel.fit(processed_df, FitModel.ModelName_LR, evaluate = true),
      FitModel.fit(processed_df, FitModel.ModelName_RF, evaluate = true)
    )

    try_models should matchPattern {
      case Success((Success(_), Success(_))) =>
    }
  }


  behavior of "Json converter"

  it should "successfully convert JsValue to DataFrame" in {
    val df = FitModel.dfFromJson(json, spark)
    df.count() shouldBe 1
    df.select("artist_latitude").head().getDouble(0) shouldBe 8.4177
  }
}