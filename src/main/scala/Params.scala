/* Unfinished. Has 3 basic parameters available */
package Chisel

import Node._
import Module._

import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer

import java.lang.reflect.{Type, ParameterizedType}

import scala.io.Source
import java.io._

//>Params.scala: Implementation of parameter framework. Defines case class
  //containers for parameter types. Params object is what actually stores
  //the data structures of parameters, whether they are generated from a Chisel
  //design, or read from a json file

case class ParamInvalidException(msg: String) extends Exception

abstract class Param[+T] {
  def init: T
  def max: Int
  def min: Int
  def pname: String
  var index: Int = -2
  var gID: Int = -1
  var register = Params.register(getComponent(), pname, this)
  //def register(pName: String) = { pname = pName; Params.register(jack.getComponent(), pname, this)}
  def getValue: T = Params.getValue(getComponent(),this.pname,this).asInstanceOf[T]
}

case class ValueParam(pname:String, init: Any) extends Param[Any] {
  val max = init.toString.toInt
  val min = init.toString.toInt
}

case class RangeParam(pname:String, init: Int, min: Int, max: Int) extends Param[Int] 

case class LessParam(pname:String, init: Int, min: Int, par: Param[Any]) extends Param[Int] {
  val max = par.max
}

case class LessEqParam(pname:String, init: Int, min: Int, par: Param[Any]) extends Param[Int] {
  val max = par.max
}

case class GreaterParam(pname:String, init: Int, par: Param[Any], max: Int) extends Param[Int] {
  val min = par.min
}

case class GreaterEqParam(pname:String, init: Int, par: Param[Any], max: Int) extends Param[Int] {
  val min = par.min
}

case class DivisorParam(pname:String, init: Int, min: Int, max: Int, par: Param[Any]) extends Param[Int]

case class EnumParam(pname:String, init: String, values: List[String]) extends Param[String] {
  val max = init.toString.toInt
  val min = init.toString.toInt
}

object IntParam {
  def apply(name: String, init: Int) = RangeParam(name, init, init, init)
}

object Params {
  type Space = ArrayBuffer[(String,Param[Any],Int)]
  var space = new Space
  var design = new Space
  var modules = new HashMap[String, Module]
  var gID: Int = 0
  
  var buildingSpace = true
  
  def getValue(module: Module, pname: String, p: Param[Any]) = {
    val mname= if(module == null) "TOP" else {module.getClass.getName}
    if(buildingSpace) p.init
    else{
      val x = design.find(t => (t._3) == (gID))
      if(x.isEmpty){ 
        throw new ParamInvalidException("Missing parameter " + pname + " in Module " + mname) 
      } else {
        x.get._2.init
      }
    }
  }
  
  def register(module: Module, pname: String, p: Param[Any]) = {
    val mname= if(module == null) "TOP" else {module.getClass.getName}
    modules(mname) = module
    if(buildingSpace) {
      space += ((mname,p,gID))
      p.gID = gID
      gID += 1
    }
    p
  }

  def load_file(filename: String) : Params.Space = {
    //val file = io.Source.fromFile(filename).mkString
    var lines = io.Source.fromFile(filename).getLines
    var space = new Params.Space
    while(lines.hasNext) {
      val line = lines.next()
      println("Loaded: " + line + "\nfrom " + filename)
      Params.deserialize(line,space)
    }
    space
  }
   
  def dump_file(filename: String, design: Params.Space) = {
    val string = Params.serialize(design)
    val writer = new PrintWriter(new File(filename))
    println("Dumping to " + filename + ":\n" + string)
    writer.write(string)
    writer.close()
  }

/*
  def load(filename: String) = {
    buildingSpace = false
    design = load_file(filename)
  }

  def dump(dir: String) = {
    dump_file(dir + "/space.prm", space)
  }*/
 
  def toCxxStringParams : String = {
    var string = new StringBuilder("")
    for ((mname, p, gID) <- space) {
      val rmname = if (mname == "TOP") "" else modules(mname).name + "__";
      string ++= "const int " + rmname + p.pname + " = " + toCxxStringParam(p) + ";\n"
    }
    string.toString
  }

  def toDotpStringParams : String = {
    var string = new StringBuilder("")
    for ((mname, p, gID) <- space) {
      val rmname = if (mname == "TOP") "" else modules(mname).name + ":";
      string ++= rmname + p.pname + " = " + toCxxStringParam(p) + "\n"
    }
    string.toString
  }

  def serialize[T<:Param[Any]](space: Space) : String = {
    var string = new StringBuilder("")
    for ((mname, p, gID) <- space) {
      string ++= mname + "," + toStringParam(p) + "\n"
    }
    string.toString
  }

  def deserialize(string: String, space: Space) = {
    val args = string.split(",")
    val mname = args(0)
    val ptype = args(1)
    val gID   = args(2).toInt
    val pname = args(3)
    val param = ptype match {
      case "value"   => { val p = new ValueParam(pname,args(4).toInt)
        p.gID = gID; p }
      case "range"   => { val p = new RangeParam(pname,args(4).toInt,args(5).toInt,args(6).toInt)
        p.gID = gID; p }
      case "less"    => { val p = new LessParam(pname,args(4).toInt,args(5).toInt,space.find(i => i._3 == args(6).toInt).get._2)
        p.gID = gID; p }
      case "lesseq"  => { val p = new LessEqParam(pname,args(4).toInt,args(5).toInt,space.find(i => i._3 == args(6).toInt).get._2)
        p.gID = gID; p }
      case "great"   => { val p = new GreaterParam(pname,args(4).toInt,space.find(i => i._3 == args(5).toInt).get._2,args(6).toInt)
        p.gID = gID; p }
      case "greateq" => { val p = new GreaterEqParam(pname,args(4).toInt,space.find(i => i._3 == args(5).toInt).get._2,args(6).toInt)
        p.gID = gID; p }
      case "divisor" => { val p = new DivisorParam(pname,args(4).toInt,args(5).toInt,args(6).toInt,space.find(i => i._3 == args(7).toInt).get._2)
        p.gID = gID; p }
      case "enum"    => { val p = new EnumParam(pname,args(4),args.slice(5,args.length).toList)
        p.gID = gID; p }
      case _         => { throw new ParamInvalidException("Unknown parameter"); new ValueParam("error",0) }
    }
    space += ((mname,param,gID.toInt))
  }
    
  def toStringParam(param: Param[Any]):String = {
    param match {
      case ValueParam(pname, init) =>
        "value,"   + param.gID + "," + pname + "," + init
      case RangeParam(pname, init, min, max) =>
        "range,"   + param.gID + "," + pname + "," + init + "," + min + "," + max
      case LessParam(pname, init, min, par) =>
        "less,"    + param.gID + "," + pname + "," + init + "," + min + "," + par.gID
      case LessEqParam(pname, init, min, par) =>
        "lesseq,"  + param.gID + "," + pname + "," + init + "," + min + "," + par.gID
      case GreaterParam(pname, init, par, max) =>
        "great,"   + param.gID + "," + pname + "," + init + "," + par.gID + "," + max
      case GreaterEqParam(pname, init, par, max) =>
        "greateq," + param.gID + "," + pname + "," + init + "," + par.gID + "," + max
      case DivisorParam(pname, init, min, max, par) =>
        "divisor," + param.gID + "," + pname + "," + init + "," + min + "," + max + "," + par.gID
      case EnumParam(pname, init, values) =>
        "enum,"    + param.gID + "," + pname + "," + init + "," + values.mkString(",")
      case _ =>
        throw new ParamInvalidException("Unknown parameter class!"); ""
    }
  }
    
  def toCxxStringParam(param: Param[Any]) = {
    param match {
      // case EnumParam(init, list) =>
        //"(range," + init + "," + list + ")"
      //   "const int " + name + " = " + init + ";\n"
      case ValueParam(pname, init) =>
        init.toString
      case RangeParam(pname, init, min, max) =>
        init.toString
      case LessParam(pname, init, min, par) =>
        init.toString
      case LessEqParam(pname, init, min, par) =>
        init.toString
      case GreaterParam(pname, init, min, par) =>
        init.toString
      case GreaterEqParam(pname, init, min, par) =>
        init.toString
      case DivisorParam(pname, init, min, max, par) =>
        init.toString
      case EnumParam(pname, init, values) =>
        init.toString
      case _ =>
        throw new ParamInvalidException("Unknown parameter class!"); ""
    }
  }
}
