/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel
import scala.collection.mutable.ArrayBuffer
import scala.math._
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import scala.sys.process._
import sys.process.stringSeqToProcess
import Node._
import Reg._
import ChiselError._
import Literal._
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap

object CString {
  def apply(s: String): String = {
    val cs = new StringBuilder("\"")
    for (c <- s) {
      if (c == '\n') {
        cs ++= "\\n"
      } else if (c == '\\' || c == '"') {
        cs ++= "\\" + c
      } else {
        cs += c
      }
    }
    cs + "\""
  }
}

class CppBackend extends Backend {
  val keywords = new HashSet[String]();
  private var hasPrintfs = false

  override def emitTmp(node: Node): String = {
    require(false)
    if (node.isInObject) {
      emitRef(node)
    } else {
      "dat_t<" + node.width + "> " + emitRef(node)
    }
  }

  override def emitRef(node: Node): String = {
    node match {
      case x: Binding =>
        emitRef(x.inputs(0))

      case x: Bits =>
        if (!node.isInObject && node.inputs.length == 1) emitRef(node.inputs(0)) else super.emitRef(node)

      case _ =>
        super.emitRef(node)
    }
  }
  def wordMangle(x: Node, w: Int): String = {
    if (w >= words(x)) {
      "0L"
    } else {
      x match {
        case l: Literal => {
          val lit = l.value
          val value = if (lit < 0) (BigInt(1) << x.width) + lit else lit
          val hex = value.toString(16)
          if (hex.length > bpw/4*w) "0x" + hex.slice(hex.length-bpw/4*(w + 1), hex.length-bpw/4*w) + "L" else "0L"
        }
        case _ => {
          if (x.isInObject)
            emitRef(x) + ".values[" + w + "]"
          else
            emitRef(x) + "__w" + w
        }
      }
    }
  }
  def emitWordRef(node: Node, w: Int): String = {
    node match {
      case x: Binding =>
        emitWordRef(x.inputs(0), w)
      case x: Bits =>
        if (!node.isInObject && node.inputs.length == 1) emitWordRef(node.inputs(0), w) else wordMangle(node, w)
      case _ =>
        wordMangle(node, w)
    }
  }

  override def emitDec(node: Node): String = {
    node match {
      case x: Binding =>
        ""
      case x: Literal =>
        ""
      case x: Reg =>
        "  dat_t<" + node.width + "> " + emitRef(node) + ";\n" +
        "  dat_t<" + node.width + "> " + emitRef(node) + "_shadow;\n";
      case m: Mem[_] =>
        "  mem_t<" + m.width + "," + m.n + "> " + emitRef(m) + ";\n"
      case r: ROMData =>
        "  mem_t<" + r.width + "," + r.lits.length + "> " + emitRef(r) + ";\n"
      case c: Clock =>
        "  int " + emitRef(node) + ";\n" +
        "  int " + emitRef(node) + "_cnt;\n";
      // case f: AsyncFIFO =>
      //   "  async_fifo_t<" + f.width + ",32> " + emitRef(f) + ";\n"
      case _ =>
        "  dat_t<" + node.width + "> " + emitRef(node) + ";\n"
    }
  }

  val bpw = 64
  def words(node: Node): Int = (node.width - 1) / bpw + 1
  def fullWords(node: Node): Int = node.width/bpw
  def emitLoWordRef(node: Node): String = emitWordRef(node, 0)
  def emitTmpDec(node: Node): String = {
    if (!node.isInObject) {
      "  val_t " + (0 until words(node)).map(emitRef(node) + "__w" + _).reduceLeft(_ + ", " + _) + ";\n"
    } else {
      ""
    }
  }
  def block(s: Seq[String]): String = 
    if (s.length == 0)
      ""
    else
      "  {" + s.map(" " + _ + ";").reduceLeft(_ + _) + " }\n"
  def makeArray(s: String, x: Node): List[String] = List("val_t " + s + "[" + words(x) + "]")
  def toArray(s: String, x: Node): List[String] = makeArray(s, x) ++ (0 until words(x)).map(i => s + "[" + i + "] = " + emitWordRef(x, i))
  def fromArray(s: String, x: Node) =
    (0 until words(x)).map(i => emitWordRef(x, i) + " = " + s + "[" + i + "]")
  def trunc(x: Node): String = {
    if (words(x) != fullWords(x)) {
      "  " + emitWordRef(x, words(x)-1) + " = " + emitWordRef(x, words(x)-1) + " & " + ((1L << (x.width-bpw*fullWords(x)))-1) + ";\n"
    } else {
      ""
    }
  }
  def opFoldLeft(o: Op, initial: (String, String) => String, subsequent: (String, String, String) => String) =
    (1 until words(o.inputs(0))).foldLeft(initial(emitLoWordRef(o.inputs(0)), emitLoWordRef(o.inputs(1))))((c, i) => subsequent(c, emitWordRef(o.inputs(0), i), emitWordRef(o.inputs(1), i)))

  def emitDefLo(node: Node): String = {
    node match {
      case x: Mux =>
        emitTmpDec(x) +
        block((0 until words(x)).map(i => emitWordRef(x, i) + " = TERNARY(" + emitLoWordRef(x.inputs(0)) + ", " + emitWordRef(x.inputs(1), i) + ", " + emitWordRef(x.inputs(2), i) + ")"))

      case o: Op => {
        emitTmpDec(o) +
        (if (o.inputs.length == 1) {
          (if (o.op == "|") {
            "  " + emitLoWordRef(o) + " = (" + (0 until words(o.inputs(0))).map(emitWordRef(o.inputs(0), _)).reduceLeft(_ + " | " + _) + ") != 0;\n"
          } else if (o.op == "&") {
            "  " + emitLoWordRef(o) + " = " + (0 until words(o.inputs(0))).map(i => "(" + emitWordRef(o.inputs(0), i) + " == " + (if (o.inputs(0).width - i*bpw < bpw) (1L << (o.inputs(0).width - i*bpw))-1 else "(val_t)-1") + ")").reduceLeft(_ + " & " + _) + ";\n"
          } else if (o.op == "^") {
            val res = ArrayBuffer[String]()
            res += "val_t __x = " + (0 until words(o.inputs(0))).map(emitWordRef(o.inputs(0), _)).reduceLeft(_ + " ^ " + _)
            for (i <- log2Up(min(bpw, o.inputs(0).width))-1 to 0 by -1)
              res += "__x = (__x >> " + (1L << i) + ") ^ __x"
            res += emitLoWordRef(o) + " = __x & 1"
            block(res)
          } else if (o.op == "~") {
            block((0 until words(o)).map(i => emitWordRef(o, i) + " = ~" + emitWordRef(o.inputs(0), i))) + trunc(o)
          } else if (o.op == "-") {
            block((0 until words(o)).map(i => emitWordRef(o, i) + " = -" + emitWordRef(o.inputs(0), i) + (if (i > 0) " - __borrow" else if (words(o) > 1) "; val_t __borrow" else "") + (if (i < words(o)-1) "; __borrow = " + emitWordRef(o.inputs(0), i) + " || " + emitWordRef(o, i) else ""))) + trunc(o)
          } else if (o.op == "!") {
            "  " + emitLoWordRef(o) + " = !" + emitLoWordRef(o.inputs(0)) + ";\n"
          } else if (o.op == "f-")
            "  " + emitLoWordRef(o) + " = fromFloat(-(toFloat(" + emitLoWordRef(o.inputs(0)) + "));\n"
          else if (o.op == "fsin")
            "  " + emitLoWordRef(o) + " = fromFloat(sin(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fcos")
            "  " + emitLoWordRef(o) + " = fromFloat(cos(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "ftan")
            "  " + emitLoWordRef(o) + " = fromFloat(tan(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fasin")
            "  " + emitLoWordRef(o) + " = fromFloat(asin(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "facos")
            "  " + emitLoWordRef(o) + " = fromFloat(acos(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fatan")
            "  " + emitLoWordRef(o) + " = fromFloat(atan(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fsqrt")
            "  " + emitLoWordRef(o) + " = fromFloat(sqrt(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "flog")
            "  " + emitLoWordRef(o) + " = fromFloat(log(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "ffloor")
            "  " + emitLoWordRef(o) + " = fromFloat(floor(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fceil")
            "  " + emitLoWordRef(o) + " = fromFloat(ceil(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fround")
            "  " + emitLoWordRef(o) + " = fromFloat(round(toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "fToSInt")
            "  " + emitLoWordRef(o) + " = (val_t)(toFloat(" + emitLoWordRef(o.inputs(0)) + "));\n"
          else if (o.op == "d-")
            "  " + emitLoWordRef(o) + " = fromDouble(-(toDouble(" + emitLoWordRef(o.inputs(0)) + "));\n"
          else if (o.op == "dsin")
            "  " + emitLoWordRef(o) + " = fromDouble(sin(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dcos")
            "  " + emitLoWordRef(o) + " = fromDouble(cos(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dtan")
            "  " + emitLoWordRef(o) + " = fromDouble(tan(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dasin")
            "  " + emitLoWordRef(o) + " = fromDouble(asin(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dacos")
            "  " + emitLoWordRef(o) + " = fromDouble(acos(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "datan")
            "  " + emitLoWordRef(o) + " = fromDouble(atan(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dlog")
            "  " + emitLoWordRef(o) + " = fromDouble(log(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dsqrt")
            "  " + emitLoWordRef(o) + " = fromDouble(sqrt(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dfloor")
            "  " + emitLoWordRef(o) + " = fromDouble(floor(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dceil")
            "  " + emitLoWordRef(o) + " = fromDouble(ceil(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dround")
            "  " + emitLoWordRef(o) + " = fromDouble(round(toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
          else if (o.op == "dToSInt")
            "  " + emitLoWordRef(o) + " = (val_t)(toDouble(" + emitLoWordRef(o.inputs(0)) + "));\n"
          else {
            assert(false, "operator " + o.op + " unsupported")
            ""
          })
        } else if (o.op == "+" || o.op == "-") {
          val res = ArrayBuffer[String]()
          res += emitLoWordRef(o) + " = " + emitLoWordRef(o.inputs(0)) + o.op + emitLoWordRef(o.inputs(1))
          for (i <- 1 until words(o)) {
            var carry = emitWordRef(o.inputs(0), i-1) + o.op + emitWordRef(o.inputs(1), i-1)
            if (o.op == "+") {
              carry += " < " + emitWordRef(o.inputs(0), i-1) + (if (i > 1) " || " + emitWordRef(o, i-1) + " < __c" else "")
            } else {
              carry += " > " + emitWordRef(o.inputs(0), i-1) + (if (i > 1) " || " + carry + " < " + emitWordRef(o, i-1) else "")
            }
            res += (if (i == 1) "val_t " else "") + "__c = " + carry
            res += emitWordRef(o, i) + " = " + emitWordRef(o.inputs(0), i) + o.op + emitWordRef(o.inputs(1), i) + o.op + "__c"
          }
          block(res) + trunc(o)
        } else if (o.op == "/") {
          val cmd = "div_n(__d, __x, __y, " + o.width + ", " + o.inputs(0).width + ", " + o.inputs(1).width + ")"
          block(makeArray("__d", o) ++ toArray("__x", o.inputs(0)) ++ toArray("__y", o.inputs(1)) ++ List(cmd) ++ fromArray("__d", o))
        } else if (o.op == "*") {
          if (o.width <= bpw) {
            "  " + emitLoWordRef(o) + " = " + emitLoWordRef(o.inputs(0)) + " * " + emitLoWordRef(o.inputs(1)) + ";\n"
          } else {
            val cmd = "mul_n(__d, __x, __y, " + o.width + ", " + o.inputs(0).width + ", " + o.inputs(1).width + ")"
            block(makeArray("__d", o) ++ toArray("__x", o.inputs(0)) ++ toArray("__y", o.inputs(1)) ++ List(cmd) ++ fromArray("__d", o))
          }
        } else if (o.op == "<<") {
          if (o.width <= bpw) {
            "  " + emitLoWordRef(o) + " = " + emitLoWordRef(o.inputs(0)) + " << " + emitLoWordRef(o.inputs(1)) + ";\n"
          } else {
            var shb = emitLoWordRef(o.inputs(1))
            val res = ArrayBuffer[String]()
            res ++= toArray("__x", o.inputs(0))
            res += "val_t __c = 0"
            res += "val_t __w = " + emitLoWordRef(o.inputs(1)) + " / " + bpw
            res += "val_t __s = " + emitLoWordRef(o.inputs(1)) + " % " + bpw
            res += "val_t __r = " + bpw + " - __s"
            for (i <- 0 until words(o)) {
              res += "val_t __v" + i + " = MASK(__x[CLAMP(" + i + "-__w,0," + (words(o.inputs(0)) - 1) + ")]," + i + ">=__w&&" + i + "<__w+" + words(o.inputs(0)) + ")"
              res += emitWordRef(o, i) + " = __v" + i + " << __s | __c"
              res += "__c = MASK(__v" + i + " >> __r, __s != 0)"
            }
            block(res) + trunc(o)
          }
        } else if (o.op == ">>" || o.op == "s>>") {
          val arith = o.op == "s>>"
          if (o.inputs(0).width <= bpw) {
            if (arith) {
              ("  " + emitLoWordRef(o) + " = (sval_t)("
                + emitLoWordRef(o.inputs(0)) + " << "
                + (bpw - o.inputs(0).width) + ") >> ("
                + (bpw - o.inputs(0).width) + " + "
                + emitLoWordRef(o.inputs(1)) + ");\n" + trunc(o))
            } else {
              ("  " + emitLoWordRef(o) + " = "
                + emitLoWordRef(o.inputs(0)) + " >> "
                + emitLoWordRef(o.inputs(1)) + ";\n")
            }
          } else {
            var shb = emitLoWordRef(o.inputs(1))
            val res = ArrayBuffer[String]()
            res ++= toArray("__x", o.inputs(0))
            res += "val_t __c = 0"
            res += "val_t __w = " + emitLoWordRef(o.inputs(1)) + " / " + bpw
            res += "val_t __s = " + emitLoWordRef(o.inputs(1)) + " % " + bpw
            res += "val_t __r = " + bpw + " - __s"
            if (arith) {
              res += "val_t __msb = (sval_t)" + emitWordRef(o.inputs(0), words(o)-1) + (if (o.width % bpw != 0) " << " + (bpw-o.width%bpw) else "") + " >> " + (bpw-1)
            }
            for (i <- words(o)-1 to 0 by -1) {
              res += "val_t __v" + i + " = MASK(__x[CLAMP(" + i + "+__w,0," + (words(o.inputs(0))-1) + ")],__w+" + i + "<" + words(o.inputs(0)) + ")"
              res += emitWordRef(o, i) + " = __v" + i + " >> __s | __c"
              res += "__c = MASK(__v" + i + " << __r, __s != 0)"
              if (arith) {
                res += emitWordRef(o, i) + " |= MASK(__msb << ((" + (o.width-1) + "-" + emitLoWordRef(o.inputs(1)) + ") % " + bpw + "), " + ((i + 1) * bpw) + " > " + (o.width-1) + "-" + emitLoWordRef(o.inputs(1)) + ")"
                res += emitWordRef(o, i) + " |= MASK(__msb, " + (i*bpw) + " >= " + (o.width-1) + "-" + emitLoWordRef(o.inputs(1)) + ")"
              }
            }
            if (arith) {
              res += emitLoWordRef(o) + " |= MASK(__msb << ((" + (o.width-1) + "-" + emitLoWordRef(o.inputs(1)) + ") % " + bpw + "), " + bpw + " > " + (o.width-1) + "-" + emitLoWordRef(o.inputs(1)) + ")"
            }
            block(res) + (if (arith) trunc(o) else "")
          }
        } else if (o.op == "##") {
          val lsh = o.inputs(1).width
          block((0 until fullWords(o.inputs(1))).map(i => emitWordRef(o, i) + " = " + emitWordRef(o.inputs(1), i)) ++
                (if (lsh % bpw != 0) List(emitWordRef(o, fullWords(o.inputs(1))) + " = " + emitWordRef(o.inputs(1), fullWords(o.inputs(1))) + " | " + emitLoWordRef(o.inputs(0)) + " << " + (lsh % bpw)) else List()) ++
                (words(o.inputs(1)) until words(o)).map(i => emitWordRef(o, i)
                  + " = " + emitWordRef(o.inputs(0), (bpw*i-lsh)/bpw)
                  + (
                    if (lsh % bpw != 0) {
                      " >> " + (bpw - lsh % bpw) + (
                        if ((bpw*i-lsh)/bpw + 1 < words(o.inputs(0))) {
                          " | " + emitWordRef(o.inputs(0), (bpw*i-lsh)/bpw + 1) + " << " + (lsh%bpw)
                        } else {
                          ""
                        })
                    } else {
                      ""
                    })))
        } else if (o.op == "|" || o.op == "&" || o.op == "^" || o.op == "||" || o.op == "&&") {
          block((0 until words(o)).map(i => emitWordRef(o, i) + " = " + emitWordRef(o.inputs(0), i) + o.op + emitWordRef(o.inputs(1), i)))
        } else if (o.op == "s<") {
          require(o.inputs(1).litOf.value == 0)
          val shamt = (o.inputs(0).width-1) % bpw
          "  " + emitLoWordRef(o) + " = (" + emitWordRef(o.inputs(0), words(o.inputs(0))-1) + " >> " + shamt + ") & 1;\n"
        } else if (o.op == "<" || o.op == "<=") {
          val initial = (a: String, b: String) => a + o.op + b
          val subsequent = (i: String, a: String, b: String) => "(" + i + ") & " + a + " == " + b + " || " + a + o.op(0) + b
          val cond = opFoldLeft(o, initial, subsequent)
          "  " + emitLoWordRef(o) + " = " + opFoldLeft(o, initial, subsequent) + ";\n"
        } else if (o.op == "==") {
          val initial = (a: String, b: String) => a + " == " + b
          val subsequent = (i: String, a: String, b: String) => "(" + i + ") & (" + a + " == " + b + ")"
          "  " + emitLoWordRef(o) + " = " + opFoldLeft(o, initial, subsequent) + ";\n"
        } else if (o.op == "!=") {
          val initial = (a: String, b: String) => a + " != " + b
          val subsequent = (i: String, a: String, b: String) => "(" + i + ") | (" + a + " != " + b + ")"
          "  " + emitLoWordRef(o) + " = " + opFoldLeft(o, initial, subsequent) + ";\n"
        } else if (o.op == "f-") {
            "  " + emitLoWordRef(o) + " = fromFloat(toFloat(" + emitLoWordRef(o.inputs(0)) + ") - toFloat(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "f+") {
            "  " + emitLoWordRef(o) + " = fromFloat(toFloat(" + emitLoWordRef(o.inputs(0)) + ") + toFloat(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "f*") {
            "  " + emitLoWordRef(o) + " = fromFloat(toFloat(" + emitLoWordRef(o.inputs(0)) + ") * toFloat(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "f/") {
            "  " + emitLoWordRef(o) + " = fromFloat(toFloat(" + emitLoWordRef(o.inputs(0)) + ") / toFloat(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "f%") {
            "  " + emitLoWordRef(o) + " = fromFloat(fmodf(toFloat(" + emitLoWordRef(o.inputs(0)) + "), toFloat(" + emitLoWordRef(o.inputs(1)) + ")));\n"
        } else if (o.op == "fpow") {
            "  " + emitLoWordRef(o) + " = fromFloat(pow(toFloat(" + emitLoWordRef(o.inputs(1)) + "), toFloat(" + emitLoWordRef(o.inputs(0)) + ")));\n"
        } else if (o.op == "f==") {
            "  " + emitLoWordRef(o) + " = toFloat(" + emitLoWordRef(o.inputs(0)) + ") == toFloat(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "f!=") {
            "  " + emitLoWordRef(o) + " = toFloat(" + emitLoWordRef(o.inputs(0)) + ") != toFloat(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "f>") {
            "  " + emitLoWordRef(o) + " = toFloat(" + emitLoWordRef(o.inputs(0)) + ") > toFloat(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "f<=") {
            "  " + emitLoWordRef(o) + " = toFloat(" + emitLoWordRef(o.inputs(0)) + ") <= toFloat(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "f>=") {
            "  " + emitLoWordRef(o) + " = toFloat(" + emitLoWordRef(o.inputs(0)) + ") >= toFloat(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "d-") {
            "  " + emitLoWordRef(o) + " = fromDouble(toDouble(" + emitLoWordRef(o.inputs(0)) + ") - toDouble(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "d+") {
            "  " + emitLoWordRef(o) + " = fromDouble(toDouble(" + emitLoWordRef(o.inputs(0)) + ") + toDouble(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "d*") {
            "  " + emitLoWordRef(o) + " = fromDouble(toDouble(" + emitLoWordRef(o.inputs(0)) + ") * toDouble(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "d/") {
            "  " + emitLoWordRef(o) + " = fromDouble(toDouble(" + emitLoWordRef(o.inputs(0)) + ") / toDouble(" + emitLoWordRef(o.inputs(1)) + "));\n"
        } else if (o.op == "d%") {
            "  " + emitLoWordRef(o) + " = fromDouble(fmod(toDouble(" + emitLoWordRef(o.inputs(0)) + "), toDouble(" + emitLoWordRef(o.inputs(1)) + ")));\n"
        } else if (o.op == "dpow") {
            "  " + emitLoWordRef(o) + " = fromDouble(pow(toDouble(" + emitLoWordRef(o.inputs(1)) + "), toDouble(" + emitLoWordRef(o.inputs(0)) + ")));\n"
        } else if (o.op == "d==") {
            "  " + emitLoWordRef(o) + " = toDouble(" + emitLoWordRef(o.inputs(0)) + ") == toDouble(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "d!=") {
            "  " + emitLoWordRef(o) + " = toDouble(" + emitLoWordRef(o.inputs(0)) + ") != toDouble(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "d>") {
            "  " + emitLoWordRef(o) + " = toDouble(" + emitLoWordRef(o.inputs(0)) + ") > toDouble(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "d<=") {
            "  " + emitLoWordRef(o) + " = toDouble(" + emitLoWordRef(o.inputs(0)) + ") <= toDouble(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else if (o.op == "d>=") {
            "  " + emitLoWordRef(o) + " = toDouble(" + emitLoWordRef(o.inputs(0)) + ") >= toDouble(" + emitLoWordRef(o.inputs(1)) + ");\n"
        } else {
          assert(false, "operator " + o.op + " unsupported")
          ""
        })
      }

      case x: Extract =>
        x.inputs.tail.foreach(e => x.validateIndex(e))
        emitTmpDec(node) +
        (if (node.inputs.length < 3 || node.width == 1) {
          if (node.inputs(1).isLit) {
            val value = node.inputs(1).value.toInt
            "  " + emitLoWordRef(node) + " = (" + emitWordRef(node.inputs(0), value/bpw) + " >> " + (value%bpw) + ") & 1;\n"
          } else if (node.inputs(0).width <= bpw) {
            "  " + emitLoWordRef(node) + " = (" + emitLoWordRef(node.inputs(0)) + " >> " + emitLoWordRef(node.inputs(1)) + ") & 1;\n"
          } else {
            block(toArray("__e", node.inputs(0)) ++ List(emitLoWordRef(node) + " = __e[" + emitLoWordRef(node.inputs(1)) + "/" + bpw + "] >> (" + emitLoWordRef(node.inputs(1)) + "%" + bpw + ") & 1"))
          }
        } else {
          val rsh = node.inputs(2).value.toInt
          if (rsh % bpw == 0) {
            block((0 until words(node)).map(i => emitWordRef(node, i) + " = " + emitWordRef(node.inputs(0), i + rsh/bpw))) + trunc(node)
          } else {
            block((0 until words(node)).map(i => emitWordRef(node, i)
              + " = " + emitWordRef(node.inputs(0), i + rsh/bpw) + " >> "
              + (rsh % bpw) + (
                if (i + rsh/bpw + 1 < words(node.inputs(0))) {
                  " | " + emitWordRef(node.inputs(0), i + rsh/bpw + 1) + " << " + (bpw - rsh % bpw)
                } else {
                  ""
                }))) + trunc(node)
          }
        })

      case x: Clock =>
        ""

      case x: Bits =>
        if (x.isInObject && x.inputs.length == 1) {
          emitTmpDec(x) + block((0 until words(x)).map(i => emitWordRef(x, i)
            + " = " + emitWordRef(x.inputs(0), i)))
        } else if (x.inputs.length == 0 && !x.isInObject) {
          emitTmpDec(x) + block((0 until words(x)).map(i => emitWordRef(x, i)
            + " = rand_val()")) + trunc(x)
        } else {
          ""
        }

      case m: MemRead =>
        emitTmpDec(m) + block((0 until words(m)).map(i => emitWordRef(m, i)
          + " = " + emitRef(m.mem) + ".get(" + emitLoWordRef(m.addr) + ", "
          + i + ")"))

      case r: ROMRead =>
        emitTmpDec(r) + block((0 until words(r)).map(i => emitWordRef(r, i)
          + " = " + emitRef(r.rom) + ".get(" + emitLoWordRef(r.addr) + ", "
          + i + ")"))

      case reg: Reg =>
        def updateData(w: Int): String = if (reg.isReset) "TERNARY(" + emitLoWordRef(reg.inputs.last) + ", " + emitWordRef(reg.init, w) + ", " + emitWordRef(reg.next, w) + ")" else emitWordRef(reg.next, w)

        def shadow(w: Int): String = emitRef(reg) + "_shadow.values[" + w + "]"
        block((0 until words(reg)).map(i => shadow(i) + " = " + updateData(i)))

      case x: Log2 =>
        (emitTmpDec(x) + "  " + emitLoWordRef(x) + " = "
          + (words(x.inputs(0))-1 to 1 by -1).map(
            i => emitWordRef(x.inputs(0), i) + " != 0, "
              + (i*bpw) + " + log2_1("
              + emitWordRef(x.inputs(0), i) + ")").foldRight("log2_1("
                + emitLoWordRef(x.inputs(0)) + ")")(
            "TERNARY(" + _ + ", " + _ + ")") + ";\n")

      case a: Assert =>
        val cond = emitLoWordRef(a.cond) +
          (if (emitRef(a.cond) == "reset") "" else " || reset.lo_word()")
        "  ASSERT(" + cond + ", " + CString(a.message) + ");\n"

      case s: Sprintf =>
        ("#if __cplusplus >= 201103L\n"
          + "  " + emitRef(s) + " = dat_format<" + s.width + ">("
          + s.args.map(emitRef _).foldLeft(CString(s.format))(_ + ", " + _)
          + ");\n"
          + "#endif\n")

      case _ =>
        ""
    }
  }

  def emitDefHi(node: Node): String = {
    node match {
      case reg: Reg =>
        "  " + emitRef(reg) + " = " + emitRef(reg) + "_shadow;\n"
      case _ => ""
    }
  }

  def emitInit(node: Node): String = {
    node match {
      case x: Clock =>
        if (x.srcClock != null) {
          "  " + emitRef(node) + " = " + emitRef(x.srcClock) + x.initStr +
          "  " + emitRef(node) + "_cnt = " + emitRef(node) + ";\n"
        } else
          ""
      case x: Reg =>
        "  if (rand_init) " + emitRef(node) + ".randomize();\n"

      case x: Mem[_] =>
        "  if (rand_init) " + emitRef(node) + ".randomize();\n"

      case r: ROMData =>
        val res = new StringBuilder
        for (i <- 0 until r.lits.length)
          res append block((0 until words(r)).map(j => emitRef(r) + ".put(" + i + ", " + j + ", " + emitWordRef(r.lits(i), j) + ")"))
        res.toString

      case u: Bits => 
        if (u.driveRand && u.isInObject)
          "  if (rand_init) " + emitRef(node) + ".randomize();\n"
        else
          ""
      case _ =>
        ""
    }
  }

  def emitInitHi(node: Node): String = {
    node match {
      case m: MemWrite =>
        // schedule before Reg updates in case a MemWrite input is a Reg
        if (m.inputs.length == 2)
          ""
        else
          block((0 until words(m)).map(i =>
            "if (" + emitLoWordRef(m.cond) + ") " + emitRef(m.mem) +
            ".put(" + emitLoWordRef(m.addr) + ", " +
            i + ", " +
            emitWordRef(m.data, i) + ")"))

      case _ =>
        ""
    }
  }

  def clkName (clock: Clock): String =
    (if (clock == Module.implicitClock) "" else "_" + emitRef(clock))

  def genHarness(c: Module, name: String) {
    val harness  = createOutputFile(name + "-emulator.cpp");
    harness.write("#include \"" + name + ".h\"\n\n");
    if (Module.clocks.length > 1) {
      harness.write("void " + c.name + "_t::setClocks ( std::vector< int > &periods ) {\n");
      var i = 0;
      for (clock <- Module.clocks) {
        if (clock.srcClock == null) {
          harness.write("  " + emitRef(clock) + " = periods[" + i + "];\n")
          harness.write("  " + emitRef(clock) + "_cnt = periods[" + i + "];\n")
          i += 1;
        }
      }
      harness.write("}\n\n");
    }
    harness.write(s"""int main (int argc, char* argv[]) {\n""");
    harness.write(s"""  ${name}_t* module = new ${name}_t();\n""");
    harness.write(s"""  module->init();\n""");
    harness.write(s"""  ${name}_api_t* api = new ${name}_api_t();\n""");
    harness.write(s"""  api->init(module);\n""");
    if (Module.isVCD) {
      harness.write(s"""  FILE *f = fopen("${name}.vcd", "w");\n""");
    } else {
      harness.write(s"""  FILE *f = NULL;\n""");
    }
    if (Module.dumpTestInput) {
      harness.write(s"""  FILE *tee = fopen("${name}.stdin", "w");\n""");
    } else {
      harness.write(s"""  FILE *tee = NULL;""");
    }
    harness.write(s"""  module->set_dumpfile(f);\n""");
    harness.write(s"""  api->set_teefile(tee);\n""");
    harness.write(s"""  api->read_eval_print_loop();\n""");
    harness.write(s"""  fclose(f);\n""");
    harness.write(s"""  fclose(tee);\n""");
    harness.write(s"""}\n""");
    harness.close();
  }

  override def compile(c: Module, flagsIn: String) {
    val flags = if (flagsIn == null) "-O2" else flagsIn

    val chiselENV = java.lang.System.getenv("CHISEL")
    val c11 = if (hasPrintfs) " -std=c++11 " else ""
    val allFlags = flags + c11 + " -I../ -I" + chiselENV + "/csrc/"
    val dir = Module.targetDir + "/"
    def run(cmd: String) {
      val bashCmd = Seq("bash", "-c", cmd)
      val c = bashCmd.!
      ChiselError.info(cmd + " RET " + c)
    }
    def link(name: String) {
      val ac = "g++ -o " + dir + name + " " + dir + name + ".o " + dir + name + "-emulator.o"
      run(ac)
    }
    def cc(name: String) {
      val cmd = "g++ -c -o " + dir + name + ".o " + allFlags + " " + dir + name + ".cpp"
      run(cmd)
    }
    cc(c.name + "-emulator")
    cc(c.name)
    link(c.name)
  }

  def emitDefLos(c: Module): String = {
    var res = "";
    for ((n, w) <- c.wires) {
      w match {
        case io: Bits  =>
          if (io.dir == INPUT) {
            res += "  " + emitRef(c) + "->" + n + " = " + emitRef(io.inputs(0)) + ";\n";
          }
      };
    }
    res += emitRef(c) + "->clock_lo(reset);\n";
    for ((n, w) <- c.wires) {
      w match {
        case io: Bits =>
          if (io.dir == OUTPUT) {
            res += "  " + emitRef(io.consumers(0)) + " = " + emitRef(c) + "->" + n + ";\n";
          }
      };
    }
    res
  }

  def emitDefHis(c: Module): String = {
    var res = emitRef(c) + "->clock_hi(reset);\n";
    res
  }

  /** Ensures each node such that it has a unique name accross the whole
    hierarchy by prefixing its name by a component path (except for "reset"
    and all nodes in *c*). */
  def renameNodes(c: Module, nodes: Seq[Node]) {
    for (m <- nodes) {
      m match {
        case l: Literal => ;
        case any        =>
          if (m.name != "" && !(m == c.defaultResetPin) && !(m.component == null)) {
            // only modify name if it is not the reset signal or not in top component
            if(m.name != "reset" || !(m.component == c)) {
              m.name = m.component.getPathName + "__" + m.name;
            }
          }
      }
    }
  }

  /**
   * Takes a list of nodes and returns a list of tuples with the names attached.
   * Used to preserve original node names before the rename process.
   */
  def generateNodeMapping(nodes: Seq[Node]): ArrayBuffer[Tuple2[String, Node]] = {
    val mappings = new ArrayBuffer[Tuple2[String, Node]]
    for (m <- nodes) {
      if (m.chiselName != "") {
        val mapping = (m.chiselName, m)
        mappings += mapping
      }
    }
    return mappings
  }

  def emitMapping(mapping: Tuple2[String, Node]): String = {
    val (name, node) = mapping
    node match {
      case x: Binding =>
        ""
      case x: Literal =>
        ""
      case x: Reg =>
        s"""  dat_table["${name}"] = new dat_api<${node.width}>(&mod_typed->${emitRef(node)}, "${name}", "");\n"""
      case m: Mem[_] =>
        s"""  mem_table["${name}"] = new mem_api<${m.width}, ${m.n}>(&mod_typed->${emitRef(node)}, "${name}", "");\n"""
      case r: ROMData =>
        s"""  mem_table["${name}"] = new mem_api<${r.width}, ${r.lits.length}>(&mod_typed->${emitRef(node)}, "${name}", "");\n"""
      case c: Clock =>
        s"""  dat_table["${name}"] = new dat_api<${node.width}>(&mod_typed->${emitRef(node)}, "${name}", "");\n"""
      case _ =>
        s"""  dat_table["${name}"] = new dat_api<${node.width}>(&mod_typed->${emitRef(node)}, "${name}", "");\n"""
    }
  }

  def backendElaborate(c: Module) = super.elaborate(c)

  override def elaborate(c: Module): Unit = {
    println("CPP elaborate")
    super.elaborate(c)

    /* We flatten all signals in the toplevel component after we had
     a change to associate node and components correctly first
     otherwise we are bound for assertions popping up left and right
     in the Backend.elaborate method. */
    for (cc <- Module.components) {
      if (!(cc == c)) {
        c.debugs ++= cc.debugs
        c.mods       ++= cc.mods;
      }
    }
    c.findConsumers();
    c.verifyAllMuxes;
    ChiselError.checkpoint()

    c.collectNodes(c);
    c.findOrdering(); // search from roots  -- create omods
    val mappings = generateNodeMapping(c.omods);
    renameNodes(c, c.omods);
    if (Module.isReportDims) {
      val (numNodes, maxWidth, maxDepth) = c.findGraphDims();
      ChiselError.info("NUM " + numNodes + " MAX-WIDTH " + maxWidth + " MAX-DEPTH " + maxDepth);
    }

    val clkDomains = new HashMap[Clock, (StringBuilder, StringBuilder)]
    for (clock <- Module.clocks) {
      val clock_lo = new StringBuilder
      val clock_hi = new StringBuilder
      clkDomains += (clock -> ((clock_lo, clock_hi)))
      clock_lo.append("void " + c.name + "_t::clock_lo" + clkName(clock) + " ( dat_t<1> reset ) {\n")
      clock_hi.append("void " + c.name + "_t::clock_hi" + clkName(clock) + " ( dat_t<1> reset ) {\n")
    }

    if (Module.isGenHarness) {
      genHarness(c, c.name);
    }
    val out_h = createOutputFile(c.name + ".h");
    if (!Params.space.isEmpty) {
      val out_p = createOutputFile(c.name + ".p");
      out_p.write(Params.toDotpStringParams);
      out_p.close();
    }
    
    // Generate header file
    out_h.write("#ifndef __" + c.name + "__\n");
    out_h.write("#define __" + c.name + "__\n\n");
    out_h.write("#include \"emulator.h\"\n\n");
    
    // Generate module headers
    out_h.write("class " + c.name + "_t : public mod_t {\n");
    out_h.write(" public:\n");
    val vcd = new VcdBackend()
    for (m <- c.omods) {
      if(m.name != "reset") {
        if (m.isInObject) {
          out_h.write(emitDec(m));
        }
        if (m.isInVCD) {
          out_h.write(vcd.emitDec(m));
        }
      }
    }
    for (clock <- Module.clocks)
      out_h.write(emitDec(clock))

    out_h.write("\n");
    out_h.write("  void init ( bool rand_init = false );\n");
    for ( clock <- Module.clocks) {
      out_h.write("  void clock_lo" + clkName(clock) + " ( dat_t<1> reset );\n")
      out_h.write("  void clock_hi" + clkName(clock) + " ( dat_t<1> reset );\n")
    }
    out_h.write("  int clock ( dat_t<1> reset );\n")
    if (Module.clocks.length > 1) {
      out_h.write("  void setClocks ( std::vector< int >& periods );\n")
    }
    out_h.write("  void print ( FILE* f );\n");
    out_h.write("  void dump ( FILE* f, int t );\n");
    out_h.write("};\n\n");
    out_h.write(Params.toCxxStringParams);
    
    // Generate API headers
    out_h.write(s"class ${c.name}_api_t : public mod_api_t {\n");
    out_h.write(s"  void init_mapping_table();\n");
    out_h.write(s"};\n\n");
    
    out_h.write("\n\n#endif\n");
    out_h.close();

    // Generate CPP files
    val out_cpps = ArrayBuffer[java.io.FileWriter]()
    val all_cpp = new StringBuilder
    def createCppFile(suffix: String = "-" + out_cpps.length) = {
      val f = createOutputFile(c.name + suffix + ".cpp")
      f.write("#include \"" + c.name + ".h\"\n")
      for (str <- Module.includeArgs) f.write("#include \"" + str + "\"\n")
      f.write("\n")
      out_cpps += f
      f
    }
    def writeCppFile(s: String) = {
      out_cpps.last.write(s)
      all_cpp.append(s)
    }

    createCppFile()
    
    // generate init block
    writeCppFile("void " + c.name + "_t::init ( bool rand_init ) {\n")
    for (m <- c.omods) {
      writeCppFile(emitInit(m))
    }
    for (clock <- Module.clocks) {
      writeCppFile(emitInit(clock))
    }
    writeCppFile("}\n")

    for (m <- c.omods) {
      val clock = if (m.clock == null) Module.implicitClock else m.clock
      clkDomains(clock)._1.append(emitDefLo(m))
    }

    for (m <- c.omods) {
      val clock = if (m.clock == null) Module.implicitClock else m.clock
      clkDomains(clock)._2.append(emitInitHi(m))
    }

    for (m <- c.omods) {
      val clock = if (m.clock == null) Module.implicitClock else m.clock
      clkDomains(clock)._2.append(emitDefHi(m))
    }

    for (clk <- clkDomains.keys) {
      clkDomains(clk)._1.append("}\n")
      clkDomains(clk)._2.append("}\n")
    }

    // generate clock(...) function
    writeCppFile("int " + c.name + "_t::clock ( dat_t<1> reset ) {\n")
    writeCppFile("  uint32_t min = ((uint32_t)1<<31)-1;\n")
    for (clock <- Module.clocks) {
      writeCppFile("  if (" + emitRef(clock) + "_cnt < min) min = " + emitRef(clock) +"_cnt;\n")
    }
    for (clock <- Module.clocks) {
      writeCppFile("  " + emitRef(clock) + "_cnt-=min;\n")
    }
    for (clock <- Module.clocks) {
      writeCppFile("  if (" + emitRef(clock) + "_cnt == 0) clock_lo" + clkName(clock) + "( reset );\n")
    }
    for (clock <- Module.clocks) {
      writeCppFile("  if (" + emitRef(clock) + "_cnt == 0) clock_hi" + clkName(clock) + "( reset );\n")
    }
    for (clock <- Module.clocks) {
      writeCppFile("  if (" + emitRef(clock) + "_cnt == 0) " + emitRef(clock) + "_cnt = " +
                  emitRef(clock) + ";\n")
    }
    writeCppFile("  return min;\n")
    writeCppFile("}\n")

    // geenrate print(...) function
    writeCppFile("void " + c.name + "_t::print ( FILE* f ) {\n")
    for (cc <- Module.components; p <- cc.printfs) {
      hasPrintfs = true
      writeCppFile("#if __cplusplus >= 201103L\n"
        + "  if (" + emitLoWordRef(p.cond)
        + ") dat_fprintf<" + p.width + ">(f, "
        + p.args.map(emitRef _).foldLeft(CString(p.format))(_ + ", " + _)
        + ");\n"
        + "#endif\n")
    }
    if (hasPrintfs)
      writeCppFile("fflush(f);\n");
    writeCppFile("}\n")

    createCppFile()
    vcd.dumpVCD(c, writeCppFile)

    for (out <- clkDomains.values.map(_._1) ++ clkDomains.values.map(_._2)) {
      createCppFile()
      writeCppFile(out.result)
    }

    // Generate API functions
    createCppFile()
    writeCppFile(s"void ${c.name}_api_t::init_mapping_table() {\n");
    writeCppFile(s"  dat_table.clear();\n")
    writeCppFile(s"  mem_table.clear();\n")
    writeCppFile(s"  ${c.name}_t* mod_typed = dynamic_cast<${c.name}_t*>(module);\n")
    writeCppFile(s"  assert(mod_typed);\n")
    for (m <- mappings) {
      if (m._2.name != "reset" && (m._2.isInObject || m._2.isInVCD)) {
        writeCppFile(emitMapping(m))
      }
    }
    writeCppFile(s"}\n");
    
    createCppFile("")
    writeCppFile(all_cpp.result)
    out_cpps.foreach(_.close)

    def copyToTarget(filename: String) = {
	  val resourceStream = getClass().getResourceAsStream("/" + filename)
	  if( resourceStream != null ) {
	    val classFile = createOutputFile(filename)
	    while(resourceStream.available > 0) {
	      classFile.write(resourceStream.read())
	    }
	    classFile.close()
	    resourceStream.close()
	  } else {
		println(s"WARNING: Unable to copy '$filename'" )
	  }
    }
    /* Copy the emulator headers into the targetDirectory. */
    copyToTarget("emulator_mod.h")
    copyToTarget("emulator_api.h")
    copyToTarget("emulator.h")
  }

}
