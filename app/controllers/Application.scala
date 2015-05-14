package controllers

import java.util.Date

import _root_.common.{myTypes, Shared}
import argonaut.Argonaut._
import argonaut._
import com.avaje.ebean._
import models._
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, JsValue, Reads}
import play.api.mvc._
import shade.memcached.Memcached

import scala.collection.JavaConversions._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect._
import scala.reflect.runtime.universe._
import scala.reflect.runtime.{universe => ru}

object Application extends Controller with myTypes {

  val m = ru.runtimeMirror(getClass.getClassLoader)

  var colOrder = Seq.empty[String] //new ArrayBuffer[String]()
  var colMap = scala.collection.mutable.Map[String, String]()
  var pList = new java.util.HashMap[String, Object]()
  val datePattern = "yyyy-MM-dd"

  val byMonthsql = "select sum(s.credit) as credit, " +
    "sum(s.debit) as debit, " +
    "cast(extract(month from s.trandate) as text) as period, " +
    " 'N' as periodType " +
    "from Transactions s " +
    "where extract(year from s.trandate) = :year and " +
    "s.userid = :userid " +
    "group by cast(extract(month from s.trandate) as text) " +
    "order by cast(extract(month from s.trandate) as text)"
  val byQuartersql = "select sum(s.credit) as credit, " +
    "sum(s.debit) as debit, " +
    "cast(extract(quarter from s.trandate) as text) as period, " +
    " 'N' as periodType " +
    "from Transactions s " +
    "where extract(year from s.trandate) = :year and " +
    "s.userid = :userid " +
    "group by cast(extract(quarter from s.trandate) as text) " +
    "order by cast(extract(quarter from s.trandate) as text)"
  val byCategorysql = "select sum(u.credit) as credit, " +
    "sum(u.debit) as debit, " +
    "u.trantype as period, " +
    "'S' as periodType " +
    "from Transactions u " +
    "where extract(year from u.trandate) = :year and " +
    "   u.userid = :userid " +
    "group by u.trantype " +
    "order by u.trantype"
  val getYearssql = "select distinct extract(year from t.trandate) as year " +
    "from Transactions t " +
    "where userid = :userid " +
    "order by 1";
  val byEmailsql = "select u.id, u.username, u.password, u.role, " +
    "u.nodata, u.joined_date, u.activation, u.active_timestamp, u.active " +
    "from Member m, Uzer u " +
    "where m.uid = u.id and " +
    "m.email = :email"
  val byUsernamesql = "select m.id, m.email, m.fname, m.lname, m.phone_number, m.type, m.street1, m.street2, " +
    "m.city, m.state, m.country, m.joined_date, m.ip,  m.zip, m.userid, u.id " +
    "from Member m, Uzer u " +
    "where m.uid = u.id and " +
    "u.username = :username"

  val myCache:Memcached = Shared.memd.get
  val minDuration:Duration = 1.milli
  val maxDuration:Duration = 2.milli

  def index = Action {
    Ok(views.html.index("Your new application is ready."))
  }

  def genSql(query: String, colMap: scala.collection.mutable.Map[String, String]): RawSql = {
    val rawSqlBld = RawSqlBuilder.parse(query)
    for ((k, v) <- colMap) {
      rawSqlBld.columnMapping(k, v)
    }
    rawSqlBld.create()
  }

  /**
   * generate collection of T objects using getter
   * @param getter
   * @param query
   * @param colMap
   * @param pList
   * @param t
   * @tparam T
   * @return
   */
  def getList[T : TypeTag](getter: String, query: String, colMap: scala.collection.mutable.Map[String, String],
                 pList: java.util.HashMap[String, AnyRef], t: T): List[T] = {
    val rawSql = genSql(query, colMap)
    val myType = getType(t)
    val obj = methodByReflectionO[T](getter, m, myType)
    obj(rawSql, Some(pList)).asInstanceOf[List[T]]
  }

  /**
   * generate Json return
   * @param colOrder
   * @param outputPage
   * @tparam T
   * @return
   */
  def genJson[T: TypeTag : ClassTag](colOrder: Array[String], outputPage: List[T] /*, m: ru.Mirror */): Json = {
    var jRows: ArrayBuffer[Json] = new ArrayBuffer[Json](outputPage.length)
    val newRow =
      for {
        agg <- outputPage
        jRowBuf: List[Json] = jsonByReflection[T](colOrder, m, agg)
      } yield Json.obj("vals" -> jArray(jRowBuf.toList))
    jRows ++= newRow
    val jRowsList: Json = jArray(jRows.toList)
    Json.obj("data" -> jRowsList)
  }

  /**
   * reflection on Class
   * @param name
   * @param m
   * @param obj
   * @return
   */
  def methodByReflectionC(name: String, m: ru.Mirror, obj: AnyRef): MethodMirror = {
    val methodX = ru.typeOf[obj.type].decl(ru.TermName(name)).asMethod
    val im = m.reflect(obj)
    im.reflectMethod(methodX)
  }

  /**
   * reflection on Object
   * @param name
   * @param m
   * @param tru
   * @tparam T
   * @return
   */
  def methodByReflectionO[T : TypeTag](name: String, m: ru.Mirror, tru: Type): MethodMirror = {
    val modX = tru.termSymbol.asModule
    val methodX = tru.decl(ru.TermName(name)).asMethod
    val mm = m.reflectModule(modX)
    val im = m.reflect(mm.instance)
    im.reflectMethod(methodX)
  }

  def mirrorObjMatch[T : TypeTag : ClassTag](obj : Any) : Json =
    obj match {
      case _: String => jString(obj.toString)
      case _: Double => jNumber(obj.asInstanceOf[Double])
      case _: Long => jNumber(obj.asInstanceOf[Long])
      case _: Integer => jNumber(obj.asInstanceOf[Integer].toDouble)
      case _: Date => jString(obj.asInstanceOf[Date].formatted("MM/dd/yyyy"))
      case _: Any => jString("")
    }

  def jsonByReflection[T : TypeTag : ClassTag](colOrder: Array[String], m: ru.Mirror, agg: T): List[Json] = {
    val jRowBuf = new ListBuffer[Json]()
    val myType = getType(agg)
    val newRow =
      for {
        col <- colOrder
        fieldTermSymb = myType.decl(ru.TermName(col)).asTerm
        im = m.reflect[T](agg)(getClassTag(agg))
        fieldMirror = im.reflectField(fieldTermSymb)
        obj = fieldMirror.get
    } yield  { if (Option(obj) == None) jString("") else mirrorObjMatch(obj) }
    jRowBuf ++=  newRow
    jRowBuf.toList
  }

  /**
   * aggregate by month for a selected year for a particular userid
   * @param year
   * @param uid
   * @return
   */
  def byMonth(year: Integer, uid: Integer) = Action {
    colOrder = Seq("credit","debit","period","periodType")

    colMap.clear()
    colMap += "sum(s.credit)" -> "credit"
    colMap += "sum(s.debit)" -> "debit"
    colMap += "cast(extract(month from s.trandate) as text)" -> "period"
    colMap += "'N'" -> "periodType"

    pList.clear()
    pList += "year" -> year
    pList += "userid" -> uid

    val aggList = getList("allq", byMonthsql, colMap, pList, Aggregates)
    val result = genJson[Aggregates](colOrder.toArray, aggList.asInstanceOf[List[Aggregates]]) //, m)
    Ok(result.nospaces)
  }

  /**
   * aggregate by category for a particular year for a userid
   * @param year
   * @param uid
   * @return
   */
  def byCategory(year: Integer, uid: Integer) = Action.async {
    val key = s"cat-$year-$uid"
    getSeq(key).map {
      case Some(i: String) =>
        Ok(if (i != None) i else {
          colOrder = Seq("credit", "debit", "period", "periodType")

          colMap = collection.mutable.Map("sum(u.credit)" -> "credit",
            "sum(u.debit)" -> "debit",
            "u.trantype" -> "period",
            "'S'" -> "periodType")

          pList.clear()
          pList += "year" -> year
          pList += "userid" -> uid

          val aggList = getList("allq", byCategorysql, colMap, pList, Aggregates)
          if (aggList.size != 0) {
            val result = genJson[Aggregates](colOrder.toArray, aggList.asInstanceOf[List[Aggregates]]).nospaces
            setSeq(key, result)
            result
          } else """{none}"""
        })
      case t: AnyRef => Ok("broke")
    }
  }

  /**
   * pivot by Quarter
   * @param year YYYY
   * @param uid userid
   * @return
   */
  def byQuarter(year: Integer, uid: Integer) = Action.async {
    val key = s"qrt-$year-$uid"
    getSeq(key).map {
      case Some(i:String) =>
        Ok(if (i != None) i else {
          colOrder = Seq("credit","debit","period","periodType")

          colMap = collection.mutable.Map("sum(s.credit)" -> "credit",
            "sum(s.debit)" -> "debit",
            "cast(extract(quarter from s.trandate) as text)" -> "period",
            "'N'" -> "periodType")

          pList.clear()
          pList += "year" -> year
          pList += "userid" -> uid

          val aggList = getList("allq", byQuartersql, colMap, pList, Aggregates)
          if (aggList.size != 0) {
            val result = genJson[Aggregates](colOrder.toArray, aggList.asInstanceOf[List[Aggregates]]).nospaces
            setSeq(key, result)
            result
          } else """{none}"""
        })
      case t: AnyRef => Ok("broke")
    }
  }

  /**
   * find by email
   * @param email
   * @return
   */
  def byEmail(email: String)=  Action {
    colOrder = Seq("id","username","password","role","nodata","joined_date","activation","active_timestamp","active")

    colMap.clear()
    colMap += "u.id" -> "id"
    colMap += "u.username" -> "username"
    colMap += "u.password" -> "password"
    colMap += "u.role" -> "role"
    colMap += "u.nodata" -> "nodata"
    colMap += "u.joined_date" -> "joined_date"
    colMap += "u.activation" -> "activation"
    colMap += "u.active_timestamp" -> "active_timestamp"
    colMap += "u.active" -> "active"

    pList.clear()
    pList += "email" -> email

    val aggList = getList("allq", byEmailsql, colMap, pList, EmailUser)
    val result = genJson(colOrder.toArray, aggList.asInstanceOf[List[EmailUser]]) //, m)
    Ok(result.nospaces)
  }

  /**
   * show username records
   * @param username
   * @return
   */
  def byUsername(username: String) = Action {
    colMap.clear()
    colMap += "m.id" -> "id"
    colMap += "m.email" -> "email"
    colMap += "m.fname" -> "fname"
    colMap += "m.phone_number" -> "phone_number"
    colMap += "m.type" -> "type"
    colMap += "m.street1" -> "street1"
    colMap += "m.street2" -> "street2"
    colMap += "m.city" -> "city"
    colMap += "m.state" -> "state"
    colMap += "m.country" -> "country"
    colMap += "m.joined_date" -> "joined_date"
    colMap += "m.ip" -> "ip"
    colMap += "m.lname" -> "lname"
    colMap += "m.zip" -> "zip"
    colMap += "m.userid" -> "userid"
    colMap += "u.id" -> "uid"

    pList.clear()
    pList += "username" -> username

    val aggList = getList("allq", byUsernamesql, colMap, pList, MemberUser)
    val result = genJson(Member.getColOrder.toArray[String], aggList.asInstanceOf[List[MemberUser]]) //, m)
    Ok(result.nospaces)
  }

  /**
   * show years for user
   * @param uid
   * @return
   */
  def byYears(uid: Integer) = Action {
    colOrder = Seq("year")

    colMap.clear()
    colMap += "extract(year from t.trandate)" -> "year"

    pList.clear()
    pList += "userid" -> uid

    val aggList = getList("allq", getYearssql, colMap, pList, Years)
    val result = genJson(colOrder.toArray[String], aggList.asInstanceOf[List[Years]]) //, m)
    Ok(result.nospaces)
  }

  /**
   * delete properties of existing object
   * @param userid
   * @return
   */
  def dropUser(userid: Long) = Action {
    val curResult = Uzer.find(userid)
    if (curResult != None) {
      Uzer.delete(curResult.get)
      Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(userid)).nospaces)
    } else {
      BadRequest(Json.obj("status" -> jString("NF")).nospaces)
    }
  }

  /**
   * delete properties of existing object
   * @param memberid
   * @return
   */
  def dropMember(memberid: Long) = Action {
    val curResult = Member.find(memberid)
    if (curResult != None) {
      Member.delete(curResult.get)
      Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(memberid)).nospaces)
    } else {
      BadRequest(Json.obj("status" -> jString("NF")).nospaces)
    }
  }

  /**
   * delete properties of existing object
   * @param contactid
   * @return
   */
  def dropContact(contactid: Long) = Action {
    val curResult = Contact.find(contactid)
    if (curResult != None) {
      Contact.delete(curResult.get)
      Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(contactid)).nospaces)
    } else {
      BadRequest(Json.obj("status" -> jString("NF")).nospaces)
    }
  }

  /**
   * delete properties of existing object
   * @param xactid
   * @return
   */
  def dropTransaction(xactid: Long) = Action {
    val curResult = Transactions.find(xactid)
    if (curResult != None) {
      Transactions.delete(curResult.get)
      Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(xactid)).nospaces)
    } else {
      BadRequest(Json.obj("status" -> jString("NF")).nospaces)
    }
  }

  /**
   * not that nullable values become options
   */
  implicit val uzerReads: Reads[Uzer] = (
    (JsPath \ "username").read[String] and
      (JsPath \ "password").read[String] and
      (JsPath \ "role").read[String] and
      (JsPath \ "nodata").readNullable[String] and
      (JsPath \ "joined_date").read[Date] and
      (JsPath \ "activation").readNullable[String] and
      (JsPath \ "active_timestamp").readNullable[Date] and
      (JsPath \ "active").read[String]
    )(Uzer.apply _)

  /**
   * post new user object
   * @return
   */
  def addUser = Action(BodyParsers.parse.json) { request =>
    val uzerRes = request.body.validate[Uzer]
    uzerRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val uzer = uzerRes.get
        Uzer.save(uzer)
        Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(uzer.id)).nospaces)
      }
    )
  }

  /**
   * almost all nullable
   */
  implicit val contactReads: Reads[Contact] = (
    //(JsPath \ "version").read[Integer] and
      (JsPath \ "bizname").readNullable[String] and
      (JsPath \ "industry").readNullable[String] and
      (JsPath \ "phone").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "identifier").readNullable[String]// and
    )(Contact.apply _)

  /**
   * post object as new contact, child of user
   * @param userid
   * @return
   */
  def addContact(userid: Long) = Action(BodyParsers.parse.json) { request =>
    //val contactJson = request.body.asJson
    val contactRes = request.body.validate[Contact]
    contactRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val contact = contactRes.get
        var parent = new Uzer
        val parentResult = Uzer.find(userid)
        if (parentResult != None) {
          parent = parentResult.get
          contact.userid = parent
          Contact.save(contact)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(contact.id)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces)
        }
      }
    )
  }

  /**
   * member has a few reqd props
   */
  implicit val memberReads: Reads[Member] = (
    (JsPath \ "email").read[String] and
      (JsPath \ "fname").read[String] and
      (JsPath \ "lname").read[String] and
      (JsPath \ "phone_number").readNullable[String] and
      (JsPath \ "type").read[String] and
      (JsPath \ "street1").readNullable[String] and
      (JsPath \ "street2").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "country").read[String] and
      (JsPath \ "joined_date").read[Date] and
      (JsPath \ "ip").read[String] and
      (JsPath \ "zip").read[String] and
      (JsPath \ "userid").readNullable[String]
    )(Member.apply _)

  /**
   * post member object, also child of user
   * @param userid
   * @return
   */
  def addMember(userid: Long) = Action(BodyParsers.parse.json) { request =>
    val memberRes = request.body.validate[Member]
    memberRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val member = memberRes.get
        val parentResult = Uzer.find(userid)
        if (parentResult != null) {
          val parent = parentResult.get
          member.uid = parent
          Member.save(member)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(member.id)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces)
        }
      }
    )
  }

  /**
   * transaction is almost all nullable
   */
  implicit val transactionReads: Reads[Transactions] = (
    (JsPath \ "trandate").read[Date] and
      (JsPath \ "acct").readNullable[String] and
      (JsPath \ "vendor").readNullable[String] and
      (JsPath \ "description").readNullable[String] and
      (JsPath \ "phone").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "debit").readNullable[Double] and
      (JsPath \ "credit").readNullable[Double] and
      (JsPath \ "trantype").readNullable[String]
    )(Transactions.apply _)

  /**
   * post a transaction object, child of user and contact
   * @param userid
   * @param contactId
   * @return
   */
  def addTransaction(userid: Long, contactId: Long) = Action(BodyParsers.parse.json) { request =>
    val transRes = request.body.validate[Transactions]
    transRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val trans = transRes.get
        val parentResult = Uzer.find(userid)
        val contactResult = Contact.find(contactId)
        if (parentResult != None && contactResult != None) {
          val parent = parentResult.get
          val contact = contactResult.get
          trans.userid = parent
          trans.contact = contact
          Transactions.save(trans)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(trans.id)).nospaces)
        } else {
          Ok(Json.obj("status" -> jString("KO")).nospaces)
        }
      }
    )
  }

  /**
   * user is all nullable for updates
   */
  val uzerUpdateReads : Reads[Uzer] = (
    (JsPath \ "username").readNullable[String] and
      (JsPath \ "password").readNullable[String] and
      (JsPath \ "role").readNullable[String] and
      (JsPath \ "nodata").readNullable[String] and
      (JsPath \ "joined_date").readNullable[Date] and
      (JsPath \ "activation").readNullable[String] and
      (JsPath \ "active_timestamp").readNullable[Date] and
      (JsPath \ "active").readNullable[String]
    )(Uzer.apply2 _)

  /**
   * update user
   * @param userid
   * @return
   */
  def updateUser(userid: Long) = Action(BodyParsers.parse.json) { request =>
    val uzerRes = request.body.validate[Uzer](uzerUpdateReads)
    uzerRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val newuzer = uzerRes.get
        val curResult = Uzer.find(userid)
        if (curResult != None) {
          val curuzer = curResult.get
          newuzer.id = userid
          Uzer.update(newuzer)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(userid)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces)
        }
      }
    )
  }

  /**
   * update member nothing required
   */
  val memberUpdateReads: Reads[Member] = (
    (JsPath \ "email").readNullable[String] and
      (JsPath \ "fname").readNullable[String] and
      (JsPath \ "lname").readNullable[String] and
      (JsPath \ "phone_number").readNullable[String] and
      (JsPath \ "type").readNullable[String] and
      (JsPath \ "street1").readNullable[String] and
      (JsPath \ "street2").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "country").readNullable[String] and
      (JsPath \ "joined_date").readNullable[Date] and
      (JsPath \ "ip").readNullable[String] and
      (JsPath \ "zip").readNullable[String] and
      (JsPath \ "userid").readNullable[String]
    )(Member.apply2 _)

  /**
   * update member
   * @param memberid
   * @return
   */
  def updateMember(memberid: Long) = Action(BodyParsers.parse.json) { request =>
    val memberRes = request.body.validate[Member](memberUpdateReads)
    memberRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val newmember = memberRes.get
        val curResult = Member.find(memberid)
        if (curResult != None) {
          val curmember = curResult.get
          newmember.id = memberid
          Member.update(newmember)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(memberid)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces)
        }
      }
    )
  }

  /**
   * update contact - nothing reqd
   */
  val contactUpdateReads: Reads[Contact] = (
    //(JsPath \ "version").read[Integer] and
    (JsPath \ "bizname").readNullable[String] and
      (JsPath \ "industry").readNullable[String] and
      (JsPath \ "phone").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "identifier").readNullable[String]
    )(Contact.apply2 _)

  /**
   * update contact object
   * @param contactid
   * @return
   */
  def updateContact(contactid: Long) = Action(BodyParsers.parse.json) { request =>
    val contactRes = request.body.validate[Contact](contactUpdateReads)
    contactRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val newcontact = contactRes.get
        val curResult = Contact.find(contactid)
        if (curResult != None) {
          val curcontact = curResult.get
          newcontact.id = contactid
          Contact.update(newcontact)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(contactid)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
        }
      }
    )
  }

  /**
   * update transaction - nothing reqd
   */
  val transactionUpdateReads: Reads[Transactions] = (
    (JsPath \ "trandate").readNullable[Date] and
      (JsPath \ "acct").readNullable[String] and
      (JsPath \ "vendor").readNullable[String] and
      (JsPath \ "description").readNullable[String] and
      (JsPath \ "phone").readNullable[String] and
      (JsPath \ "city").readNullable[String] and
      (JsPath \ "state").readNullable[String] and
      (JsPath \ "debit").readNullable[Double] and
      (JsPath \ "credit").readNullable[Double] and
      (JsPath \ "trantype").readNullable[String]
    )(Transactions.apply2 _)

  /**
   * update transaction object
   * @param xactid
   * @return
   */
  def updateTransaction(xactid: Long): Action[JsValue] = Action(BodyParsers.parse.json) { request =>
    val xactRes = request.body.validate[Transactions](transactionUpdateReads)
    xactRes.fold(
      errors => {
        BadRequest(Json.obj("status" -> jString("KO")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
      },
      uz => {
        val newxact = xactRes.get
        val curResult = Transactions.find(xactid)
        if (curResult != None) {
          val curxact = curResult.get         // needed for update below
          newxact.id = xactid
          Transactions.update(newxact)
          Ok(Json.obj("status" -> jString("OK"), "id" -> jNumber(xactid)).nospaces)
        } else {
          BadRequest(Json.obj("status" -> jString("NF")).nospaces) //, "message" -> play.api.libs.json.JsError.toFlatJson(errors)))
        }
      }
    )
  }

  /**
   *
   * actual work horse adding means only add key if not present
   * @param key string
   * @param value string
   * @return
   */
  def addSeq(key:String, value: String) : Future[Any] = {
    Future.firstCompletedOf(Seq(myCache.add(key, value, minDuration), play.api.libs.concurrent.Promise.timeout("Oops", maxDuration)))
  }

  /**
   *
   * actual work horse setting
   * @param key string
   * @param value string
   * @return
   */
  def setSeq(key:String, value: String) : Future[Any] = {
    Future.firstCompletedOf(Seq(myCache.set(key, value, minDuration), play.api.libs.concurrent.Promise.timeout("Oops", maxDuration)))
  }

  /**
   * actual work horse getting
   * @param key string
   * @return
   */
  def getSeq(key:String) : Future[Any] = {
    Future.firstCompletedOf(Seq(myCache.get[String](key), play.api.libs.concurrent.Promise.timeout("Oops", minDuration)))
  }

  /**
   * actual work horse deleting
   * @param key string
   * @return
   */
  def delSeq(key:String) : Future[Any] = {
    Future.firstCompletedOf(Seq(myCache.delete(key), play.api.libs.concurrent.Promise.timeout("Oops", maxDuration)))
  }

  /**
   * actual work horse deleting
   * @param key string
   * @return
   */
  def compareSetSeq(key:String, newVal:String) : Future[Any] = {
    Future.firstCompletedOf(Seq(myCache.compareAndSet(key, Some(key), newVal, minDuration), play.api.libs.concurrent.Promise.timeout("Oops", maxDuration)))
  }

  /**
   * set cache key to value asynchronously
   * @param key string
   * @param value string
   * @return
   */
  def memadd(key: String, value: String) = Action.async {
    addSeq(key, value).map {
      case i : Boolean   => Ok(if (i) "ok" else "exists")
      case t : AnyRef    => Ok("unset")
    }
  }

  /**
   * set cache key to value asynchronously
   * @param key string
   * @param value string
   * @return
   */
  def memset(key: String, value: String) = Action.async {
    setSeq(key, value).map {
      case i : Unit   => Ok("ok")
      case t : AnyRef => Ok("unset")
    }
  }

  /**
   * blocking version
   * @param key string
   * @return
   */
  def memgetBlock(key: String) = Action {
    val res = myCache.awaitGet[String](key) match {
      case Some(value) => value
      case None        => s"key $key not found"
    }
    Ok(res)
  }

  /**
   * asynch get key for minDuration
   * @param key string
   * @return
   */
  def memget(key: String) = Action.async {
    getSeq(key).map {
      case Some(i: String) => Ok(if (i != None) i else "nf")
      case t: AnyRef => Ok("broke")
    }
  }

  /**
   * async drop key for minDuration
   * @param key string
   * @return
   */
  def memdrop(key: String) = Action.async {
    delSeq(key).map {
      case i: Boolean => Ok(if (i) "hit" else "miss")
      case t: AnyRef => Ok("broke")
    }
  }
}
