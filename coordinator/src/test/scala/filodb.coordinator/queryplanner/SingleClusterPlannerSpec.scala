package filodb.coordinator.queryplanner

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.scalatest.{FunSpec, Matchers}

import filodb.coordinator.ShardMapper
import filodb.coordinator.client.QueryCommands.{FunctionalSpreadProvider, StaticSpreadProvider}
import filodb.core.{MetricsTestData, SpreadChange}
import filodb.core.metadata.Schemas
import filodb.core.query.{ColumnFilter, Filter}
import filodb.prometheus.ast.TimeStepParams
import filodb.prometheus.parse.Parser
import filodb.query._
import filodb.query.exec._

class SingleClusterPlannerSpec extends FunSpec with Matchers {

  implicit val system = ActorSystem()
  private val node = TestProbe().ref

  private val mapper = new ShardMapper(32)
  for { i <- 0 until 32 } mapper.registerNode(Seq(i), node)

  private def mapperRef = mapper

  private val dataset = MetricsTestData.timeseriesDataset
  private val dsRef = dataset.ref
  private val schemas = Schemas(dataset.schema)

  private val engine = new SingleClusterPlanner(dsRef, schemas, mapperRef)

  /*
  This is the PromQL

  sum(rate(http_request_duration_seconds_bucket{job="myService",le="0.3"}[5m])) by (job)
   /
  sum(rate(http_request_duration_seconds_count{job="myService"}[5m])) by (job)
  */

  val f1 = Seq(ColumnFilter("__name__", Filter.Equals("http_request_duration_seconds_bucket")),
    ColumnFilter("job", Filter.Equals("myService")),
    ColumnFilter("le", Filter.Equals("0.3")))

  val to = System.currentTimeMillis()
  val from = to - 50000

  val intervalSelector = IntervalSelector(from, to)

  val raw1 = RawSeries(rangeSelector = intervalSelector, filters= f1, columns = Seq("value"))
  val windowed1 = PeriodicSeriesWithWindowing(raw1, from, 1000, to, 5000, RangeFunctionId.Rate)
  val summed1 = Aggregate(AggregationOperator.Sum, windowed1, Nil, Seq("job"))

  val f2 = Seq(ColumnFilter("__name__", Filter.Equals("http_request_duration_seconds_count")),
    ColumnFilter("job", Filter.Equals("myService")))
  val raw2 = RawSeries(rangeSelector = intervalSelector, filters= f2, columns = Seq("value"))
  val windowed2 = PeriodicSeriesWithWindowing(raw2, from, 1000, to, 5000, RangeFunctionId.Rate)
  val summed2 = Aggregate(AggregationOperator.Sum, windowed2, Nil, Seq("job"))
  val promQlQueryParams = PromQlQueryParams("sum(heap_usage)", 100, 1, 1000, None)

  it ("should generate ExecPlan for LogicalPlan") {
    // final logical plan
    val logicalPlan = BinaryJoin(summed1, BinaryOperator.DIV, Cardinality.OneToOne, summed2)

    // materialized exec plan
    val execPlan = engine.materialize(logicalPlan, QueryContext(origQueryParams = promQlQueryParams))

    /*
    Following ExecPlan should be generated:

    BinaryJoinExec(binaryOp=DIV, on=List(), ignoring=List()) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-4#-325843755])
    -AggregatePresenter(aggrOp=Sum, aggrParams=List())
    --ReduceAggregateExec(aggrOp=Sum, aggrParams=List()) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-4#-325843755])
    ---AggregateMapReduce(aggrOp=Sum, aggrParams=List(), without=List(), by=List(job))
    ----PeriodicSamplesMapper(start=1526094025509, step=1000, end=1526094075509, window=Some(5000), functionId=Some(Rate), funcParams=List())
    -----MultiSchemaPartitionsExec(shard=2, rowKeyRange=RowKeyInterval(b[1526094025509],b[1526094075509]), filters=List(ColumnFilter(__name__,Equals(http_request_duration_seconds_bucket)), ColumnFilter(job,Equals(myService)), ColumnFilter(le,Equals(0.3)))) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-3#342951049])
    ---AggregateMapReduce(aggrOp=Sum, aggrParams=List(), without=List(), by=List(job))
    ----PeriodicSamplesMapper(start=1526094025509, step=1000, end=1526094075509, window=Some(5000), functionId=Some(Rate), funcParams=List())
    -----SelectRawPartitionsExec(shard=3, rowKeyRange=RowKeyInterval(b[1526094025509],b[1526094075509]), filters=List(ColumnFilter(__name__,Equals(http_request_duration_seconds_bucket)), ColumnFilter(job,Equals(myService)), ColumnFilter(le,Equals(0.3)))) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-4#-325843755])
    -AggregatePresenter(aggrOp=Sum, aggrParams=List())
    --ReduceAggregateExec(aggrOp=Sum, aggrParams=List()) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-2#-1576910232])
    ---AggregateMapReduce(aggrOp=Sum, aggrParams=List(), without=List(), by=List(job))
    ----PeriodicSamplesMapper(start=1526094025509, step=1000, end=1526094075509, window=Some(5000), functionId=Some(Rate), funcParams=List())
    -----SelectRawPartitionsExec(shard=0, rowKeyRange=RowKeyInterval(b[1526094025509],b[1526094075509]), filters=List(ColumnFilter(__name__,Equals(http_request_duration_seconds_count)), ColumnFilter(job,Equals(myService)))) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-1#-238515561])
    ---AggregateMapReduce(aggrOp=Sum, aggrParams=List(), without=List(), by=List(job))
    ----PeriodicSamplesMapper(start=1526094025509, step=1000, end=1526094075509, window=Some(5000), functionId=Some(Rate), funcParams=List())
    -----SelectRawPartitionsExec(shard=1, rowKeyRange=RowKeyInterval(b[1526094025509],b[1526094075509]), filters=List(ColumnFilter(__name__,Equals(http_request_duration_seconds_count)), ColumnFilter(job,Equals(myService)))) on ActorPlanDispatcher(Actor[akka://default/system/testProbe-2#-1576910232])
    */

    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual true
    execPlan.children.foreach { l1 =>
      // Now there should be single level of reduce because we have 2 shards
      l1.isInstanceOf[ReduceAggregateExec] shouldEqual true
      l1.children.foreach { l2 =>
        l2.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
        l2.rangeVectorTransformers.size shouldEqual 2
        l2.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
        l2.rangeVectorTransformers(1).isInstanceOf[AggregateMapReduce] shouldEqual true
      }
    }
  }

  it ("should parallelize aggregation") {
    val logicalPlan = BinaryJoin(summed1, BinaryOperator.DIV, Cardinality.OneToOne, summed2)

    // materialized exec plan
    val execPlan = engine.materialize(logicalPlan,
      QueryContext(promQlQueryParams, Some(StaticSpreadProvider(SpreadChange(0, 4))), 1000000))
    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual true

    // Now there should be multiple levels of reduce because we have 16 shards
    execPlan.children.foreach { l1 =>
      l1.isInstanceOf[ReduceAggregateExec] shouldEqual true
      l1.children.foreach { l2 =>
        l2.isInstanceOf[ReduceAggregateExec] shouldEqual true
        l2.children.foreach { l3 =>
          l3.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
          l3.rangeVectorTransformers.size shouldEqual 2
          l3.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
          l3.rangeVectorTransformers(1).isInstanceOf[AggregateMapReduce] shouldEqual true
        }
      }
    }
  }

  it("should materialize ExecPlan correctly for _bucket_ histogram queries") {
    val lp = Parser.queryRangeToLogicalPlan("""rate(foo{job="bar",_bucket_="2.5"}[5m])""",
      TimeStepParams(20000, 100, 30000))

    info(s"LogicalPlan is $lp")
    lp match {
      case p: PeriodicSeriesWithWindowing => p.series.isInstanceOf[ApplyInstantFunctionRaw] shouldEqual true
      case _ => throw new IllegalArgumentException(s"Unexpected LP $lp")
    }

    val execPlan = engine.materialize(lp,
      QueryContext(promQlQueryParams, Some(StaticSpreadProvider(SpreadChange(0, 4))), 1000000))

    info(s"First child plan: ${execPlan.children.head.printTree()}")
    execPlan.isInstanceOf[DistConcatExec] shouldEqual true
    execPlan.children.foreach { l1 =>
      l1.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
      l1.rangeVectorTransformers.size shouldEqual 2
      l1.rangeVectorTransformers(0).isInstanceOf[InstantVectorFunctionMapper] shouldEqual true
      l1.rangeVectorTransformers(1).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
      l1.rangeVectorTransformers(1).asInstanceOf[PeriodicSamplesMapper].rawSource shouldEqual false
    }
  }

  import com.softwaremill.quicklens._

  it("should rename Prom __name__ filters if dataset has different metric column") {
    // Custom SingleClusterPlanner with different dataset with different metric name
    val datasetOpts = dataset.options.copy(metricColumn = "kpi", shardKeyColumns = Seq("kpi", "job"))
    val dataset2 = dataset.modify(_.schema.partition.options).setTo(datasetOpts)
    val engine2 = new SingleClusterPlanner(dataset2.ref, Schemas(dataset2.schema), mapperRef)

    // materialized exec plan
    val execPlan = engine2.materialize(raw2, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[DistConcatExec] shouldEqual true
    execPlan.children.foreach { l1 =>
      l1.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
      val rpExec = l1.asInstanceOf[MultiSchemaPartitionsExec]
      rpExec.filters.map(_.column).toSet shouldEqual Set("kpi", "job")
    }
  }

  it("should use spread function to change/override spread and generate ExecPlan with appropriate shards") {
    var filodbSpreadMap = new collection.mutable.HashMap[collection.Map[String, String], Int]
    filodbSpreadMap.put(collection.Map(("job" -> "myService")), 2)

    val spreadFunc = QueryContext.simpleMapSpreadFunc(Seq("job"), filodbSpreadMap, 1)

    // final logical plan
    val logicalPlan = BinaryJoin(summed1, BinaryOperator.DIV, Cardinality.OneToOne, summed2)

    // materialized exec plan
    val execPlan = engine.materialize(logicalPlan, QueryContext(promQlQueryParams, Some(FunctionalSpreadProvider(spreadFunc)), 1000000))
    execPlan.printTree()

    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual true
    execPlan.children should have length (2)
    execPlan.children.foreach { reduceAggPlan =>
      reduceAggPlan.isInstanceOf[ReduceAggregateExec] shouldEqual true
      reduceAggPlan.children should have length (4)   // spread=2 means 4 shards
    }
  }

  it("should stitch results when spread changes during query range") {
    val lp = Parser.queryRangeToLogicalPlan("""foo{job="bar"}""", TimeStepParams(20000, 100, 30000))
    def spread(filter: Seq[ColumnFilter]): Seq[SpreadChange] = {
      Seq(SpreadChange(0, 1), SpreadChange(25000000, 2)) // spread change time is in ms
    }
    val execPlan = engine.materialize(lp, QueryContext(promQlQueryParams,
                                      Some(FunctionalSpreadProvider(spread)), 1000000))
    execPlan.rangeVectorTransformers.head.isInstanceOf[StitchRvsMapper] shouldEqual true
  }

  it("should not stitch results when spread has not changed in query range") {
    val lp = Parser.queryRangeToLogicalPlan("""foo{job="bar"}""", TimeStepParams(20000, 100, 30000))
    def spread(filter: Seq[ColumnFilter]): Seq[SpreadChange] = {
      Seq(SpreadChange(0, 1), SpreadChange(35000000, 2))
    }
    val execPlan = engine.materialize(lp, QueryContext(promQlQueryParams,
                                      Some(FunctionalSpreadProvider(spread)), 1000000))
    execPlan.rangeVectorTransformers.isEmpty shouldEqual true
  }

  it("should stitch results before binary join when spread changed in query range") {
    val lp = Parser.queryRangeToLogicalPlan("""count(foo{job="bar"} + baz{job="bar"})""",
      TimeStepParams(20000, 100, 30000))
    def spread(filter: Seq[ColumnFilter]): Seq[SpreadChange] = {
      Seq(SpreadChange(0, 1), SpreadChange(25000000, 2))
    }
    val execPlan = engine.materialize(lp, QueryContext(promQlQueryParams, Some(FunctionalSpreadProvider(spread)),
                                      1000000))
    val binaryJoinNode = execPlan.children(0)
    binaryJoinNode.isInstanceOf[BinaryJoinExec] shouldEqual true
    binaryJoinNode.children.size shouldEqual 2
    binaryJoinNode.children.foreach(_.isInstanceOf[StitchRvsExec] shouldEqual true)
  }

  it("should not stitch results before binary join when spread has not changed in query range") {
    val lp = Parser.queryRangeToLogicalPlan("""count(foo{job="bar"} + baz{job="bar"})""",
      TimeStepParams(20000, 100, 30000))
    def spread(filter: Seq[ColumnFilter]): Seq[SpreadChange] = {
      Seq(SpreadChange(0, 1), SpreadChange(35000000, 2))
    }
    val execPlan = engine.materialize(lp, QueryContext(promQlQueryParams, Some(FunctionalSpreadProvider(spread)),
                                      1000000))
    val binaryJoinNode = execPlan.children(0)
    binaryJoinNode.isInstanceOf[BinaryJoinExec] shouldEqual true
    binaryJoinNode.children.foreach(_.isInstanceOf[StitchRvsExec] should not equal true)
  }

  it ("should generate SetOperatorExec for LogicalPlan with Set operator") {
    // final logical plan
    val logicalPlan = BinaryJoin(summed1, BinaryOperator.LAND, Cardinality.ManyToMany, summed2)

    // materialized exec plan
    val execPlan = engine.materialize(logicalPlan, QueryContext(origQueryParams = promQlQueryParams))

    execPlan.isInstanceOf[SetOperatorExec] shouldEqual true
    execPlan.children.foreach { l1 =>
      // Now there should be single level of reduce because we have 2 shards
      l1.isInstanceOf[ReduceAggregateExec] shouldEqual true
      l1.children.foreach { l2 =>
        l2.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
        l2.rangeVectorTransformers.size shouldEqual 2
        l2.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
        l2.rangeVectorTransformers(1).isInstanceOf[AggregateMapReduce] shouldEqual true
      }
    }
  }

}