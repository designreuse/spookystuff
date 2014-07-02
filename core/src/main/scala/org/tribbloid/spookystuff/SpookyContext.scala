package org.tribbloid.spookystuff

import java.io.Serializable
import java.util

import org.apache.poi.ss.formula.functions.T
import org.apache.spark.rdd.RDD
import org.tribbloid.spookystuff.entity.{ActionPlan, Page}
import org.tribbloid.spookystuff.sparkbinding.{ActionPlanRDDFunctions, PageRDDFunctions, StringRDDFunctions}

/**
 * Created by peng on 12/06/14.
 */
//class SpookyContext(val sc: SparkContext) {
//
//}

//TODO: use implicit view bound to simplify things: <%
object SpookyContext {

  implicit def pageRDDToItsFunctions(rdd: RDD[Page]) = new PageRDDFunctions(rdd)

  implicit def ActionPlanRDDToItsFunctions(rdd: RDD[ActionPlan]) = new ActionPlanRDDFunctions(rdd)

  implicit def stringRDDToItsFunctions(rdd: RDD[String]) = new StringRDDFunctions(rdd)

  //these are the entry points of SpookyStuff starting from a common RDD of strings or maps
  implicit def stringRDDToActionPlanRDD(rdd: RDD[String]): RDD[ActionPlan] = rdd.map{
    str => {
      val context = new util.HashMap[String,Serializable]
      context.put("_", str.asInstanceOf[Serializable])
      new ActionPlan(context)
    }
  }

  implicit def stringRDDToActionPlanRDDFunctions(rdd: RDD[String]) = new ActionPlanRDDFunctions(stringRDDToActionPlanRDD(rdd))



  implicit def mapRDDToActionPlanRDD[T <: util.Map[String,String]](rdd: RDD[T]) = rdd.map{
    map => {
      new ActionPlan(new util.HashMap[String,Serializable](map))
    }
  }

  implicit def mapRDDToActionPlanRDDFunctions[T <: util.Map[String,String]](rdd: RDD[T]) = new ActionPlanRDDFunctions(mapRDDToActionPlanRDD(rdd))

  implicit def pageRDDToActionPlanRDD(rdd: RDD[Page]) = rdd.map{
    page => {
      new ActionPlan(page.context)
    }
  }

  implicit def pageRDDToActionPlanRDDFunctions(rdd: RDD[Page]) = new ActionPlanRDDFunctions(pageRDDToActionPlanRDD(rdd))
}
