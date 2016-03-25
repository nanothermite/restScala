package entities

import java.util.Date
import javax.persistence._
import play.api.libs.json._
import com.avaje.ebean.RawSql
import common.{BaseObject, Dao}
import org.joda.time.DateTime
import play.data.validation.Constraints
import utils.{DateUtils, DateFormatter}
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

/**
 * Created by hkatz on 9/5/14.
 */
@Entity
class Transactions extends BaseObject {
  @Id
  @GeneratedValue(strategy=GenerationType.AUTO)
  var id: Long = 0l

  @Constraints.Required
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "contact")
  var contact: Contact = null

  @Constraints.Required
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userid")
  var userid: Uzer = null

  @Constraints.Required
  var trandate: Date = null

  var acct: String = null

  var vendor: String = null

  var description: String = null

  var phone: String = null

  var city: String = null

  var state: String = null

  var debit: java.lang.Double = 0d

  var credit: java.lang.Double = 0d

  var trantype: String = null

  override def toString : String =  {
    var s = ""
    if (id != 0l && userid != null)
      s = f"$id%d - $userid"
    s
  }

  def toJSON = Json.obj(
    "id" -> id,
    "trandate" -> DateFormatter.formatDate(new DateTime(trandate)),
    "acct" -> jsonNullCheck(acct),
    "vendor" -> jsonNullCheck(vendor),
    "description" -> jsonNullCheck(description),
    "phone" -> jsonNullCheck(phone),
    "city" -> jsonNullCheck(city),
    "state" -> jsonNullCheck(state),
    "debit" -> jsonNullCheck(debit),
    "credit" -> jsonNullCheck(credit),
    "trantype" -> jsonNullCheck(trantype),
    "userid" -> jsonNullCheck(userid.id)
  )
}

object Transactions extends Dao(classOf[Transactions]) {
  val byMonth = "byMonth"
  val byQuarter = "byQuarter"
  val year = "year"

  def all(userid: Long): Option[List[Transactions]] = {
    val uzer = Uzer.find(userid.toInt)
    val objList =
      if (uzer.isDefined) {
        val objList = Transactions.find.where.eq("userid", uzer.get).findList
        if (objList.nonEmpty)
          objList.asScala.toList
        else
          List.empty[Transactions]
      }
      else
        List.empty[Transactions]
    Some(objList)
  }

  def allq(sql:RawSql) : List[Transactions] = {
    val q = find
    q.setRawSql(sql)
    q.findList().asScala.toList
  }

  def getColOrder: List[String] = List("id","trandate","acct","vendor","description","phone","city",
    "state","debit","credit","trantype","contact","userid")

  def getMetas: Map[String, Class[_]] = Map(
    "trandate" -> classOf[Date],
    "acct" -> classOf[String],
    "vendor" -> classOf[String],
    "description" -> classOf[String],
    "phone" -> classOf[String],
    "city" -> classOf[String],
    "state" -> classOf[String],
    "debit" -> classOf[Double],
    "credit" -> classOf[Double],
    "trantype" -> classOf[String]
  )

  def getReqd: Map[String, Integer] = Map("trandate" -> 1)

  def create(id: Long,contact: Contact,userid: Uzer,trandate: Date,acct: String,vendor: String,
             description: String,phone: String,city: String,state: String,debit: Double,credit: Double,
             trantype: String): Unit = {
    val trans = new Transactions
    trans.id = id
    trans.contact = contact
    trans.userid = userid
    trans.trandate = trandate
    trans.acct = acct
    trans.vendor = vendor
    trans.description = description
    trans.city = city
    trans.state = state
    trans.debit = debit
    trans.credit = credit
    trans.trantype = trantype
    save(trans)
  }

  def empty = apply(null, None, None, None, None, None, None, None, None, None, 0)

  def apply(trandate: Date,acct: Option[String],vendor: Option[String],
            description: Option[String],phone: Option[String],city: Option[String],state: Option[String],
            debit: Option[Double],credit: Option[Double], trantype: Option[String], userid: Int): Transactions = {
    val trans = new Transactions
    trans.trandate = trandate
    if (acct.isDefined)
      trans.acct = acct.get
    if (vendor.isDefined)
    trans.vendor = vendor.get
    if (description.isDefined)
      trans.description = description.get
    if (phone.isDefined)
    trans.phone = phone.get
    if (city.isDefined)
      trans.city = city.get
    if (state.isDefined)
      trans.state = state.get
    if (debit.isDefined)
      trans.debit = debit.get
    if (credit.isDefined)
      trans.credit = credit.get
    if (trantype.isDefined)
      trans.trantype = trantype.get
    trans.userid = Uzer.find(userid).get
    trans
  }

  def apply2(trandate: Option[Date],acct: Option[String],vendor: Option[String],
            description: Option[String],phone: Option[String],city: Option[String],state: Option[String],
            debit: Option[Double],credit: Option[Double], trantype: Option[String], userid: Option[String]): Transactions = {
    val trans = new Transactions
    if (trandate.isDefined)
      trans.trandate = trandate.get
    if (acct.isDefined)
      trans.acct = acct.get
    if (vendor.isDefined)
      trans.vendor = vendor.get
    if (description.isDefined)
      trans.description = description.get
    if (phone.isDefined)
      trans.phone = phone.get
    if (city.isDefined)
      trans.city = city.get
    if (state.isDefined)
      trans.state = state.get
    if (debit.isDefined)
      trans.debit = debit.get
    if (credit.isDefined)
      trans.credit = credit.get
    if (trantype.isDefined)
      trans.trantype = trantype.get
    if (userid.isDefined)
      trans.userid = Uzer.find(userid.get.toInt).get
    trans
  }

  def apply3(strs: List[String])(implicit uzer: Uzer): Transactions = {
    val userid = uzer.id.toInt
    val trandate = DateUtils.dateParse(strs(0), DateUtils.YMD).toDate
    strs.size match {
      case 1 => apply(trandate, None, None, None, None, None, None, None, None, None, userid)
      case 2 => apply(trandate, Some(strs(1)), None, None, None, None, None, None, None, None, userid)
      case 3 => apply(trandate, Some(strs(1)), Some(strs(2)), None, None, None, None, None, None, None, userid)
      case 4 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), None, None, None, None, None, None, userid)
      case 5 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), None, None, None, None, None, userid)
      case 6 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), Some(strs(5)), None, None, None, None, userid)
      case 7 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), Some(strs(5)), Some(strs(6)), None, None, None, userid)
      case 8 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), Some(strs(5)), Some(strs(6)), Some(strs(7).toDouble), None, None, userid)
      case 9 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), Some(strs(5)), Some(strs(6)), Some(strs(7).toDouble), Some(strs(8).toDouble), None, userid)
      case 10 => apply(trandate, Some(strs(1)), Some(strs(2)), Some(strs(3)), Some(strs(4)), Some(strs(5)), Some(strs(6)), Some(strs(7).toDouble), Some(strs(8).toDouble), Some(strs(9)), userid)
    }
  }

  def findBiz(vendor: String)(implicit uzer: Uzer): List[Transactions] =
    find.where.
    eq("userid", uzer).
    eq("vendor", vendor).findList.asScala.toList
}