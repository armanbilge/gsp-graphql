// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2023 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package grackle

import cats.implicits._
import io.circe.Json
import org.tpolecat.sourcepos.SourcePos

import syntax._
import Ast.{DirectiveLocation, InterfaceTypeDefinition, ObjectTypeDefinition, TypeDefinition, UnionTypeDefinition}
import Query._
import ScalarType._
import UntypedOperation._
import Value._

/**
 * Representation of a GraphQL schema
 *
 * A `Schema` is a collection of type and directive declarations.
 */
trait Schema {

  def pos: SourcePos

  /** The types defined by this `Schema`. */
  def types: List[NamedType]

  /** The directives defined by this `Schema`. */
  def directives: List[DirectiveDef]

  /** A reference by name to a type defined by this `Schema`.
   *
   * `TypeRef`s refer to types defined in this schema by name and hence
   * can be used as part of mutually recursive type definitions.
   */
  def ref(tpnme: String): TypeRef = new TypeRef(this, tpnme)

  /**
   * Alias for `ref` for use within constructors of concrete
   * `Schema` values.
   */
  protected def TypeRef(tpnme: String): TypeRef = ref(tpnme)

  /**
   * The default type of a GraphQL schema
   *
   * Unless a type named `"Schema"` is explicitly defined as part of
   * this `Schema` a definition of the form,
   *
   * ```
   * type Schema {
   *   query: Query!
   *   mutation: Mutation
   *   subscription: Subscription
   * }
   * ```
   *
   * is used.
   */
  def defaultSchemaType: NamedType = {
    def mkRootDef(fieldName: String)(tpe: NamedType): Field =
      Field(fieldName, None, Nil, tpe, Nil)

    ObjectType(
      name = "Schema",
      description = None,
      fields =
        List(
          definition("Query").map(mkRootDef("query")),
          definition("Mutation").map(mkRootDef("mutation")),
          definition("Subscription").map(mkRootDef("subscription"))
        ).flatten,
      interfaces = Nil,
      directives = Nil
    )
  }

  /**
   * Look up by name a type defined in this `Schema`.
   *
   * Yields the type, if defined, `None` otherwise.
   */
  def definition(name: String): Option[NamedType] =
    typeIndex.get(name).orElse(ScalarType.builtIn(name)).map(_.dealias)

  private lazy val typeIndex = types.map(tpe => (tpe.name, tpe)).toMap

  def ref(tp: Type): Option[TypeRef] = tp match {
    case nt: NamedType if types.exists(_.name == nt.name) => Some(ref(nt.name))
    case _ => None
  }

  /**
   * The schema type.
   *
   * Either the explicitly defined type named `"Schema"` or the default
   * schema type if not defined.
   */
  def schemaType: NamedType = definition("Schema").getOrElse(defaultSchemaType)

  /** The type of queries defined by this `Schema`*/
  def queryType: NamedType = schemaType.field("query").flatMap(_.nonNull.asNamed).get

  /** The type of mutations defined by this `Schema`*/
  def mutationType: Option[NamedType] = schemaType.field("mutation").flatMap(_.nonNull.asNamed)

  /** The type of subscriptions defined by this `Schema`*/
  def subscriptionType: Option[NamedType] = schemaType.field("subscription").flatMap(_.nonNull.asNamed)

  /** True if the supplied type is one of the Query, Mutation or Subscription root types, false otherwise */
  def isRootType(tpe: Type): Boolean =
    tpe =:= queryType || mutationType.map(_ =:= tpe).getOrElse(false) || subscriptionType.map(_ =:= tpe).getOrElse(false)

  /** Are the supplied alternatives exhaustive for `tp` */
  def exhaustive(tp: Type, branches: List[Type]): Boolean = {
    types.forall {
      case o: ObjectType => !(o <:< tp) || branches.exists(b => o <:< b)
      case _ => true
    }
  }

  override def toString = SchemaRenderer.renderSchema(this)
}

object Schema {
  def apply(schemaText: String)(implicit pos: SourcePos): Result[Schema] =
    SchemaParser.parseText(schemaText)
}

/**
 * A GraphQL type definition.
 */
sealed trait Type extends Product {
  /**
   * Is this type equivalent to `other`.
   *
   * Note that plain `==` will distinguish types from type aliases,
   * which is typically not desirable, so `=:=` is usually the
   * most appropriate comparison operator.
   */
  def =:=(other: Type): Boolean = (this eq other) || (dealias == other.dealias)

  /** `true` if this type is a subtype of `other`. */
  def <:<(other: Type): Boolean =
    (this.dealias, other.dealias) match {
      case (tp1, tp2) if tp1 == tp2 => true
      case (tp1, UnionType(_, _, members, _)) => members.exists(tp1 <:< _.dealias)
      case (ObjectType(_, _, _, interfaces, _), tp2) => interfaces.exists(_ <:< tp2)
      case (InterfaceType(_, _, _, interfaces, _), tp2) => interfaces.exists(_ <:< tp2)
      case (NullableType(tp1), NullableType(tp2)) => tp1 <:< tp2
      case (tp1, NullableType(tp2)) => tp1 <:< tp2
      case (ListType(tp1), ListType(tp2)) => tp1 <:< tp2
      case _ => false
    }

  def nominal_=:=(other: Type): Boolean =
    this =:= other ||
      ((this.dealias, other.dealias) match {
        case (nt1: NamedType, nt2: NamedType) => nt1.name == nt2.name
        case _ => false
      })

  /**
   * Yield the type of the field of this type named `fieldName` or
   * `None` if there is no such field.
   */
  def field(fieldName: String): Option[Type] = this match {
    case NullableType(tpe) => tpe.field(fieldName)
    case TypeRef(_, _) if exists => dealias.field(fieldName)
    case ObjectType(_, _, fields, _, _) => fields.find(_.name == fieldName).map(_.tpe)
    case InterfaceType(_, _, fields, _, _) => fields.find(_.name == fieldName).map(_.tpe)
    case _ => None
  }

  /** `true` if this type has a field named `fieldName`, false otherwise. */
  def hasField(fieldName: String): Boolean =
    field(fieldName).isDefined

  /** Yields the definition of `fieldName` in this type if it exists, `None` otherwise. */
  def fieldInfo(fieldName: String): Option[Field] = this match {
    case NullableType(tpe) => tpe.fieldInfo(fieldName)
    case ListType(tpe) => tpe.fieldInfo(fieldName)
    case _: TypeRef => dealias.fieldInfo(fieldName)
    case _ => None
  }

  /**
   * `true` if this type has a field named `fieldName` which is undefined in
   * some interface it implements
   */
  def variantField(fieldName: String): Boolean =
    underlyingObject match {
      case Some(ObjectType(_, _, _, interfaces, _)) =>
        hasField(fieldName) && interfaces.exists(!_.hasField(fieldName))
      case _ => false
    }

  /**
   * Yield the type of the field at the end of the path `fns` starting
   * from this type, or `None` if there is no such field.
   */
  def path(fns: List[String]): Option[Type] = (fns, this) match {
    case (Nil, _) => Some(this)
    case (_, ListType(tpe)) => tpe.path(fns)
    case (_, NullableType(tpe)) => tpe.path(fns)
    case (_, TypeRef(_, _)) => dealias.path(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).flatMap(_.tpe.path(rest))
    case (fieldName :: rest, InterfaceType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).flatMap(_.tpe.path(rest))
    case _ => None
  }

  /**
   * Does the path `fns` from this type specify multiple values.
   *
   * `true` if navigating through the path `fns` from this type
   * might specify 0 or more values. This will be the case if the
   * path passes through at least one field of a List type.
   */
  def pathIsList(fns: List[String]): Boolean = (fns, this) match {
    case (Nil, _) => this.isList
    case (_, _: ListType) => true
    case (_, NullableType(tpe)) => tpe.pathIsList(fns)
    case (_, TypeRef(_, _)) => dealias.pathIsList(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsList(rest)).getOrElse(false)
    case (fieldName :: rest, InterfaceType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsList(rest)).getOrElse(false)
    case _ => false
  }

  /**
   * Does the path `fns` from this type specify a nullable type.
   *
   * `true` if navigating through the path `fns` from this type
   * might specify an optional value. This will be the case if the
   * path passes through at least one field of a nullable type.
   */
  def pathIsNullable(fns: List[String]): Boolean = (fns, this) match {
    case (Nil, _) => false
    case (_, ListType(tpe)) => tpe.pathIsNullable(fns)
    case (_, _: NullableType) => true
    case (_, TypeRef(_, _)) => dealias.pathIsNullable(fns)
    case (fieldName :: rest, ObjectType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsNullable(rest)).getOrElse(false)
    case (fieldName :: rest, InterfaceType(_, _, fields, _, _)) =>
      fields.find(_.name == fieldName).map(_.tpe.pathIsNullable(rest)).getOrElse(false)
    case _ => false
  }

  /** Strip off aliases */
  def dealias: Type = this

  /** true if a non-TypeRef or a TypeRef to a defined type */
  def exists: Boolean = true

  /** Is this type nullable? */
  def isNullable: Boolean = this match {
    case NullableType(_) => true
    case _ => false
  }

  /** This type if it is nullable, `Nullable(this)` otherwise. */
  def nullable: Type = this match {
    case t: NullableType => t
    case t => NullableType(t)
  }

  /**
   * A non-nullable version of this type.
   *
   * If this type is nullable, yield the non-nullable underlying
   * type. Otherwise yield this type.
   */
  def nonNull: Type = this match {
    case NullableType(tpe) => tpe.nonNull
    case _ => this
  }

  /** Is this type a list. */
  def isList: Boolean = this match {
    case ListType(_) => true
    case _ => false
  }

  /**
   * The element type of this type.
   *
   * If this type is is a list, yield the non-list underlying type.
   * Otherwise yield `None`.
   */
  def item: Option[Type] = this match {
    case NullableType(tpe) => tpe.item
    case ListType(tpe) => Some(tpe)
    case _ => None
  }

  /** This type if it is a (nullable) list, `ListType(this)` otherwise. */
  def list: Type = this match {
    case l: ListType => l
    case NullableType(tpe) => NullableType(tpe.list)
    case tpe => ListType(tpe)
  }

  def underlying: Type = this match {
    case NullableType(tpe) => tpe.underlying
    case ListType(tpe) => tpe.underlying
    case _: TypeRef => dealias.underlying
    case _ => this
  }

  /**
   * Yield the object type underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying object type is reached, in which case yield it, or a
   * non-object type which isn't further reducible is reached, in which
   * case yield `None`.
   */
  def underlyingObject: Option[NamedType] = this match {
    case NullableType(tpe) => tpe.underlyingObject
    case ListType(tpe) => tpe.underlyingObject
    case _: TypeRef => dealias.underlyingObject
    case o: ObjectType => Some(o)
    case i: InterfaceType => Some(i)
    case u: UnionType => Some(u)
    case _ => None
  }

  /**
   * Yield the type of the field named `fieldName` of the object type
   * underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying object type is reached which has a field named
   * `fieldName`, in which case yield the type of that field; if there
   * is no such field, yields `None`.
   */
  def underlyingField(fieldName: String): Option[Type] = this match {
    case NullableType(tpe) => tpe.underlyingField(fieldName)
    case ListType(tpe) => tpe.underlyingField(fieldName)
    case TypeRef(_, _) => dealias.underlyingField(fieldName)
    case ObjectType(_, _, fields, _, _) => fields.find(_.name == fieldName).map(_.tpe)
    case InterfaceType(_, _, fields, _, _) => fields.find(_.name == fieldName).map(_.tpe)
    case _ => None
  }

  /** Is this type a leaf type?
   *
   * `true` if after stripping of aliases the underlying type a scalar or an
   * enum, `false` otherwise.
   */
  def isLeaf: Boolean = this match {
    case TypeRef(_, _) => dealias.isLeaf
    case _: ScalarType => true
    case _: EnumType => true
    case _ => false
  }

  /**
   * If the underlying type of this type is a scalar or an enum then yield it
   * otherwise yield `None`.
   */
  def asLeaf: Option[Type] = this match {
    case TypeRef(_, _) => dealias.asLeaf
    case _: ScalarType => Some(this)
    case _: EnumType => Some(this)
    case _ => None
  }


  /**
   * Is the underlying of this type a leaf type?
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying leaf type is reached, in which case yield true, or an
   * a object, interface or union type which is reached, in which case
   * yield false.
   */
  def isUnderlyingLeaf: Boolean = this match {
    case NullableType(tpe) => tpe.isUnderlyingLeaf
    case ListType(tpe) => tpe.isUnderlyingLeaf
    case _: TypeRef => dealias.isUnderlyingLeaf
    case (_: ObjectType)|(_: InterfaceType)|(_: UnionType) => false
    case _ => true
  }

  /**
   * Yield the leaf type underlying this type.
   *
   * Strip off all aliases, nullability and enclosing list types until
   * an underlying leaf type is reached, in which case yield it, or an
   * a object, interface or union type which is reached, in which case
   * yield `None`.
   */
  def underlyingLeaf: Option[Type] = this match {
    case NullableType(tpe) => tpe.underlyingLeaf
    case ListType(tpe) => tpe.underlyingLeaf
    case _: TypeRef => dealias.underlyingLeaf
    case (_: ObjectType)|(_: InterfaceType)|(_: UnionType) => None
    case tpe => Some(tpe)
  }

  def withModifiersOf(tpe: Type): Type = {
    def loop(rtpe: Type, tpe: Type): Type = tpe match {
      case NullableType(tpe) => loop(NullableType(rtpe), tpe)
      case ListType(tpe) => loop(ListType(rtpe), tpe)
      case _ => rtpe
    }
    loop(this, tpe)
  }

  def isNamed: Boolean = false

  def asNamed: Option[NamedType] = None

  def isInterface: Boolean = false

  def isUnion: Boolean = false

  def /(pathElement: String): Path =
    Path.from(this) / pathElement

  def directives: List[Directive]
}

// Move all below into object Type?

/** A type with a schema-defined name.
 *
 * This includes object types, inferface types and enums.
 */
sealed trait NamedType extends Type {
  /** The name of this type */
  def name: String

  override def dealias: NamedType = this

  override def isNamed: Boolean = true

  override def asNamed: Option[NamedType] = Some(this)

  def description: Option[String]

  def directives: List[Directive]

  override def toString: String = name
}

/**
 * A by name reference to a type defined in `schema`.
 */
case class TypeRef(schema: Schema, name: String) extends NamedType {
  override lazy val dealias: NamedType = schema.definition(name).getOrElse(this)

  override lazy val exists: Boolean = schema.definition(name).isDefined

  def description: Option[String] = dealias.description

  def directives: List[Directive] = dealias.directives
}

/**
 * Represents scalar types such as Int, String, and Boolean. Scalars cannot have fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Scalar
 */
case class ScalarType(
  name: String,
  description: Option[String],
  directives: List[Directive]
) extends Type with NamedType {
  import ScalarType._

  /** True if this is one of the five built-in Scalar types defined in the GraphQL Specification. */
  def isBuiltIn: Boolean =
    this match {
      case IntType     |
           FloatType   |
           StringType  |
           BooleanType |
           IDType      => true
      case _           => false
    }

}

object ScalarType {
  def builtIn(tpnme: String): Option[ScalarType] = tpnme match {
    case "Int" => Some(IntType)
    case "Float" => Some(FloatType)
    case "String" => Some(StringType)
    case "Boolean" => Some(BooleanType)
    case "ID" => Some(IDType)
    case _ => None
  }

  val IntType = ScalarType(
    name = "Int",
    description =
      Some(
        """|The Int scalar type represents a signed 32‐bit numeric non‐fractional value.
           |Response formats that support a 32‐bit integer or a number type should use that
           |type to represent this scalar.
        """.stripMargin.trim
      ),
    directives = Nil
  )
  val FloatType = ScalarType(
    name = "Float",
    description =
      Some(
        """|The Float scalar type represents signed double‐precision fractional values as
           |specified by IEEE 754. Response formats that support an appropriate
           |double‐precision number type should use that type to represent this scalar.
        """.stripMargin.trim
      ),
    directives = Nil
  )
  val StringType = ScalarType(
    name = "String",
    description =
      Some(
        """|The String scalar type represents textual data, represented as UTF‐8 character
           |sequences. The String type is most often used by GraphQL to represent free‐form
           |human‐readable text.
        """.stripMargin.trim
      ),
    directives = Nil
  )
  val BooleanType = ScalarType(
    name = "Boolean",
    description =
      Some(
        """|The Boolean scalar type represents true or false. Response formats should use a
           |built‐in boolean type if supported; otherwise, they should use their
           |representation of the integers 1 and 0.
        """.stripMargin.trim
      ),
    directives = Nil
  )

  val IDType = ScalarType(
    name = "ID",
    description =
      Some(
        """|The ID scalar type represents a unique identifier, often used to refetch an
           |object or as the key for a cache. The ID type is serialized in the same way as a
           |String; however, it is not intended to be human‐readable.
        """.stripMargin.trim
      ),
    directives = Nil
  )

  val AttributeType = ScalarType(
    name = "InternalAttribute",
    description = None,
    directives = Nil
  )
}

/**
 * A type with fields.
 *
 * This includes object types and inferface types.
 */
sealed trait TypeWithFields extends NamedType {
  def fields: List[Field]
  def interfaces: List[NamedType]

  override def fieldInfo(name: String): Option[Field] = fields.find(_.name == name)
}

/**
 * Interfaces are an abstract type where there are common fields declared. Any type that
 * implements an interface must define all the fields with names and types exactly matching.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Interface
 */
case class InterfaceType(
  name: String,
  description: Option[String],
  fields: List[Field],
  interfaces: List[NamedType],
  directives: List[Directive]
) extends Type with TypeWithFields {
  override def isInterface: Boolean = true
}

/**
 * Object types represent concrete instantiations of sets of fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Object
 */
case class ObjectType(
  name: String,
  description: Option[String],
  fields: List[Field],
  interfaces: List[NamedType],
  directives: List[Directive]
) extends Type with TypeWithFields

/**
 * Unions are an abstract type where no common fields are declared. The possible types of a union
 * are explicitly listed out in elements. Types can be made parts of unions without
 * modification of that type.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Union
 */
case class UnionType(
  name: String,
  description: Option[String],
  members: List[NamedType],
  directives: List[Directive]
) extends Type with NamedType {
  override def isUnion: Boolean = true
  override def toString: String = members.mkString("|")
}

/**
 * Enums are special scalars that can only have a defined set of values.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Enum
 */
case class EnumType(
  name: String,
  description: Option[String],
  enumValues: List[EnumValueDefinition],
  directives: List[Directive]
) extends Type with NamedType {
  def hasValue(name: String): Boolean = enumValues.exists(_.name == name)

  def value(name: String): Option[EnumValue] = valueDefinition(name).map(_ => EnumValue(name))
  def valueDefinition(name: String): Option[EnumValueDefinition] = enumValues.find(_.name == name)
}

/**
 * The `EnumValue` type represents one of possible values of an enum.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__EnumValue-Type
 */
case class EnumValueDefinition(
  name: String,
  description: Option[String],
  directives: List[Directive]
) {
  def deprecatedDirective: Option[Directive] =
    directives.find(_.name == "deprecated")
  def isDeprecated: Boolean = deprecatedDirective.isDefined
  def deprecationReason: Option[String] =
    for {
      dir    <- deprecatedDirective
      reason <- dir.args.collectFirst { case Binding("reason", StringValue(reason)) => reason }
    } yield reason
}

/**
 * Input objects are composite types used as inputs into queries defined as a list of named input
 * values.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Input-Object
 */
case class InputObjectType(
  name: String,
  description: Option[String],
  inputFields: List[InputValue],
  directives: List[Directive]
) extends Type with NamedType {
  def inputFieldInfo(name: String): Option[InputValue] = inputFields.find(_.name == name)
}

/**
 * Lists represent sequences of values in GraphQL. A List type is a type modifier: it wraps
 * another type instance in the ofType field, which defines the type of each item in the list.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Type-Kinds.List
 */
case class ListType(
  ofType: Type
) extends Type {
  def directives: List[Directive] = Nil
  override def toString: String = s"[$ofType]"
}

/**
 * A Non‐null type is a type modifier: it wraps another type instance in the `ofType` field.
 * Non‐null types do not allow null as a response, and indicate required inputs for arguments
 * and input object fields.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-Type-Kinds.Non-Null
 */
case class NullableType(
  ofType: Type
) extends Type {
  def directives: List[Directive] = Nil
  override def toString: String = s"$ofType?"
}

/**
 * The `Field` type represents each field in an Object or Interface type.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__Field-Type
 */
case class Field(
  name: String,
  description: Option[String],
  args: List[InputValue],
  tpe: Type,
  directives: List[Directive]
) {
  def deprecatedDirective: Option[Directive] =
    directives.find(_.name == "deprecated")
  def isDeprecated: Boolean = deprecatedDirective.isDefined
  def deprecationReason: Option[String] =
    for {
      dir    <- deprecatedDirective
      reason <- dir.args.collect { case Binding("reason", StringValue(reason)) => reason }.headOption
    } yield reason
}

/**
 * @param defaultValue a String encoding (using the GraphQL language) of the default value used by
 *                     this input value in the condition a value is not provided at runtime.
 */
case class InputValue(
  name: String,
  description: Option[String],
  tpe: Type,
  defaultValue: Option[Value],
  directives: List[Directive]
)

sealed trait Value

object Value {

  case class IntValue(value: Int) extends Value

  case class FloatValue(value: Double) extends Value

  case class StringValue(value: String) extends Value

  case class BooleanValue(value: Boolean) extends Value

  case class IDValue(value: String) extends Value

  case class EnumValue(name: String) extends Value

  case class ListValue(elems: List[Value]) extends Value

  case class ObjectValue(fields: List[(String, Value)]) extends Value

  case class VariableRef(name: String) extends Value

  case object NullValue extends Value

  case object AbsentValue extends Value

  object StringListValue {
    def apply(ss: List[String]): Value =
      ListValue(ss.map(StringValue(_)))

    def unapply(value: Value): Option[List[String]] =
      value match {
        case ListValue(l) => l.traverse {
          case StringValue(s) => Some(s)
          case _ => None
        }
        case _ => None
      }
  }

  /**
   * Elaborate a value by replacing variable references with their values.
   */
  def elaborateValue(value: Value, vars: Vars): Result[Value] = {
    def loop(value: Value): Result[Value] =
      value match {
        case VariableRef(varName) =>
          Result.fromOption(vars.get(varName).map(_._2), s"Undefined variable '$varName'")
        case ObjectValue(fields) =>
          val (keys, values) = fields.unzip
          values.traverse(loop).map(evs => ObjectValue(keys.zip(evs)))
        case ListValue(elems) => elems.traverse(loop).map(ListValue.apply)
        case other => Result(other)
      }
    loop(value)
  }

  /**
   * Resolve a value against its definition.
   *
   * + Absent and null values are defaulted if the InputValue provides a default.
   * + Absent and null values are checked against the nullability of the InputValue.
   * + Enum values are checked against the possible values of the EnumType.
   * + Primitive values are converted to custom Scalars or IDs where appropriate.
   * + The elements of list values are checked against their element type.
   * + The fields of input object values are checked against their field definitions.
   */
  def checkValue(iv: InputValue, value: Option[Value], location: String): Result[Value] =
    (iv.tpe.dealias, value) match {
      case (_, None) if iv.defaultValue.isDefined =>
        iv.defaultValue.get.success
      case (_: NullableType, None) =>
        AbsentValue.success
      case (_: NullableType, Some(AbsentValue)) =>
        AbsentValue.success
      case (_: NullableType, Some(NullValue)) =>
        NullValue.success
      case (NullableType(tpe), Some(_)) =>
        checkValue(iv.copy(tpe = tpe), value, location)
      case (IntType, Some(value: IntValue)) =>
        value.success
      case (FloatType, Some(value: FloatValue)) =>
        value.success
      case (StringType, Some(value: StringValue)) =>
        value.success
      case (BooleanType, Some(value: BooleanValue)) =>
        value.success

      // Custom Scalars
      case (s: ScalarType, Some(value: IntValue)) if !s.isBuiltIn =>
        value.success
      case (s: ScalarType, Some(value: FloatValue)) if !s.isBuiltIn =>
        value.success
      case (s: ScalarType, Some(value: StringValue)) if !s.isBuiltIn =>
        value.success
      case (s: ScalarType, Some(value: BooleanValue)) if !s.isBuiltIn =>
        value.success

      case (IDType, Some(value: IDValue)) =>
        value.success
      case (IDType, Some(StringValue(s))) =>
        IDValue(s).success
      case (IDType, Some(IntValue(i))) =>
        IDValue(i.toString).success
      case (e: EnumType, Some(value@EnumValue(name))) if e.hasValue(name) =>
        value.success
      case (ListType(tpe), Some(ListValue(arr))) =>
        arr.traverse { elem =>
          checkValue(iv.copy(tpe = tpe, defaultValue = None), Some(elem), location)
        }.map(ListValue.apply)
      case (InputObjectType(nme, _, ivs, _), Some(ObjectValue(fs))) =>
        val obj = fs.toMap
        val unknownFields = fs.map(_._1).filterNot(f => ivs.exists(_.name == f))
        if (unknownFields.nonEmpty)
          Result.failure(s"Unknown field(s) ${unknownFields.map(s => s"'$s'").mkString("", ", ", "")} for input object value of type ${nme} in $location")
        else
          ivs.traverse(iv => checkValue(iv, obj.get(iv.name), location).map(v => (iv.name, v))).map(ObjectValue.apply)
      case (tpe, Some(value)) => Result.failure(s"Expected $tpe found '${SchemaRenderer.renderValue(value)}' for '${iv.name}' in $location")
      case (tpe, None) => Result.failure(s"Value of type $tpe required for '${iv.name}' in $location")
    }

  /**
   * Resolve a Json variable value against its definition.
   *
   * + Absent and null values are defaulted if the InputValue provides a default.
   * + Absent and null values are checked against the nullability of the InputValue.
   * + Enum values are checked against the possible values of the EnumType.
   * + Primitive values are converted to custom Scalars or IDs where appropriate.
   * + The elements of list values are checked against their element type.
   * + The fields of input object values are checked against their field definitions.
   */
  def checkVarValue(iv: InputValue, value: Option[Json], location: String): Result[Value] = {
    import JsonExtractor._

    (iv.tpe.dealias, value) match {
      case (_, None) if iv.defaultValue.isDefined =>
        iv.defaultValue.get.success
      case (_: NullableType, None) =>
        AbsentValue.success
      case (_: NullableType, Some(jsonNull(_))) =>
        NullValue.success
      case (NullableType(tpe), Some(_)) =>
        checkVarValue(iv.copy(tpe = tpe), value, location)
      case (IntType, Some(jsonInt(value))) =>
        IntValue(value).success
      case (FloatType, Some(jsonDouble(value))) =>
        FloatValue(value).success
      case (StringType, Some(jsonString(value))) =>
        StringValue(value).success
      case (BooleanType, Some(jsonBoolean(value))) =>
        BooleanValue(value).success
      case (IDType, Some(jsonInt(value))) =>
        IDValue(value.toString).success

      // Custom scalars
      case (s: ScalarType, Some(jsonInt(value))) if !s.isBuiltIn =>
        IntValue(value).success
      case (s: ScalarType, Some(jsonDouble(value))) if !s.isBuiltIn =>
        FloatValue(value).success
      case (s: ScalarType, Some(jsonString(value))) if !s.isBuiltIn =>
        StringValue(value).success
      case (s: ScalarType, Some(jsonBoolean(value))) if !s.isBuiltIn =>
        BooleanValue(value).success

      case (IDType, Some(jsonString(value))) =>
        IDValue(value).success
      case (e: EnumType, Some(jsonString(name))) if e.hasValue(name) =>
        EnumValue(name).success
      case (ListType(tpe), Some(jsonArray(arr))) =>
        arr.traverse { elem =>
          checkVarValue(iv.copy(tpe = tpe, defaultValue = None), Some(elem), location)
        }.map(vs => ListValue(vs.toList))
      case (InputObjectType(nme, _, ivs, _), Some(jsonObject(obj))) =>
        val unknownFields = obj.keys.filterNot(f => ivs.exists(_.name == f))
        if (unknownFields.nonEmpty)
          Result.failure(s"Unknown field(s) ${unknownFields.map(s => s"'$s'").mkString("", ", ", "")} in input object value of type ${nme} in $location")
        else
          ivs.traverse(iv => checkVarValue(iv, obj(iv.name), location).map(v => (iv.name, v))).map(ObjectValue.apply)
      case (tpe, Some(value)) => Result.failure(s"Expected $tpe found '$value' for '${iv.name}' in $location")
      case (tpe, None) => Result.failure(s"Value of type $tpe required for '${iv.name}' in $location")
    }
  }
}

/**
 * The `Directive` type represents a Directive that a server supports.
 *
 * @see https://facebook.github.io/graphql/draft/#sec-The-__Directive-Type
 */
case class DirectiveDef(
  name: String,
  description: Option[String],
  args: List[InputValue],
  isRepeatable: Boolean,
  locations: List[DirectiveLocation]
)

object DirectiveDef {
  val Skip: DirectiveDef =
    DirectiveDef(
      "skip",
      Some(
        """|The @skip directive may be provided for fields, fragment spreads, and inline
           |fragments, and allows for conditional exclusion during execution as described
           |by the if argument.
        """.stripMargin.trim
      ),
      List(InputValue("if", Some("Skipped with true."), BooleanType, None, Nil)),
      false,
      List(DirectiveLocation.FIELD, DirectiveLocation.FRAGMENT_SPREAD, DirectiveLocation.INLINE_FRAGMENT)
    )

  val Include: DirectiveDef =
    DirectiveDef(
      "include",
      Some(
        """|The @include directive may be provided for fields, fragment spreads, and inline
           |fragments, and allows for conditional inclusion during execution as described
           |by the if argument.
        """.stripMargin.trim
      ),
      List(InputValue("if", Some("Included when true."), BooleanType, None, Nil)),
      false,
      List(DirectiveLocation.FIELD, DirectiveLocation.FRAGMENT_SPREAD, DirectiveLocation.INLINE_FRAGMENT)
    )

  val Deprecated: DirectiveDef =
    DirectiveDef(
      "deprecated",
      Some(
        """|The @deprecated directive is used within the type system definition language
           |to indicate deprecated portions of a GraphQL service’s schema, such as deprecated
           |fields on a type or deprecated enum values.
        """.stripMargin.trim
      ),
      List(InputValue("reason", Some("Explains why this element was deprecated, usually also including a suggestion for how to access supported similar data. Formatted using the Markdown syntax, as specified by [CommonMark](https://commonmark.org/)."), NullableType(StringType), Some(StringValue("No longer supported")), Nil)),
      false,
      List(DirectiveLocation.FIELD_DEFINITION, DirectiveLocation.ENUM_VALUE)
    )

  val builtIns: List[DirectiveDef] =
    List(Skip, Include, Deprecated)
}

case class Directive(
  name: String,
  args: List[Binding]
)

object Directive {
  def validateDirectivesForSchema(schema: Schema): List[Problem] = {
    def validateTypeDirectives(tpe: NamedType): List[Problem] =
      tpe match {
        case o: ObjectType =>
          validateDirectives(schema, Ast.DirectiveLocation.OBJECT, o.directives, Map.empty) ++
          o.fields.flatMap(validateFieldDirectives)
        case i: InterfaceType =>
          validateDirectives(schema, Ast.DirectiveLocation.INTERFACE, i.directives, Map.empty) ++
          i.fields.flatMap(validateFieldDirectives)
        case u: UnionType =>
          validateDirectives(schema, Ast.DirectiveLocation.UNION, u.directives, Map.empty)
        case e: EnumType =>
          validateDirectives(schema, Ast.DirectiveLocation.ENUM, e.directives, Map.empty) ++
          e.enumValues.flatMap(v => validateDirectives(schema, Ast.DirectiveLocation.ENUM_VALUE, v.directives, Map.empty))
        case s: ScalarType =>
          validateDirectives(schema, Ast.DirectiveLocation.SCALAR, s.directives, Map.empty)
        case i: InputObjectType =>
          validateDirectives(schema, Ast.DirectiveLocation.INPUT_OBJECT, i.directives, Map.empty) ++
          i.inputFields.flatMap(f => validateDirectives(schema, Ast.DirectiveLocation.INPUT_FIELD_DEFINITION, f.directives, Map.empty))
        case _ => Nil
      }

    def validateFieldDirectives(field: Field): List[Problem] =
      validateDirectives(schema, Ast.DirectiveLocation.FIELD_DEFINITION, field.directives, Map.empty) ++
      field.args.flatMap(a => validateDirectives(schema, Ast.DirectiveLocation.ARGUMENT_DEFINITION, a.directives, Map.empty))

    validateDirectives(schema, Ast.DirectiveLocation.SCHEMA, schema.schemaType.directives, Map.empty) ++
    (schema.schemaType match {
      case twf: TypeWithFields => twf.fields.flatMap(validateFieldDirectives)
      case _ => Nil
    }) ++
    schema.types.flatMap(validateTypeDirectives)
  }

  def validateDirectivesForQuery(schema: Schema, op: UntypedOperation, frags: List[UntypedFragment], vars: Vars): Result[Unit] = {
    def queryWarnings(query: Query): List[Problem] = {
      def loop(query: Query): List[Problem] =
        query match {
          case UntypedSelect(_, _, _, dirs, child) =>
            validateDirectives(schema, Ast.DirectiveLocation.FIELD, dirs, vars) ++ loop(child)
          case UntypedFragmentSpread(_, dirs) =>
            validateDirectives(schema, Ast.DirectiveLocation.FRAGMENT_SPREAD, dirs, vars)
          case UntypedInlineFragment(_, dirs, child) =>
            validateDirectives(schema, Ast.DirectiveLocation.INLINE_FRAGMENT, dirs, vars) ++ loop(child)
          case Select(_, _, child)       => loop(child)
          case Group(children)           => children.flatMap(loop)
          case Narrow(_, child)          => loop(child)
          case Unique(child)             => loop(child)
          case Filter(_, child)          => loop(child)
          case Limit(_, child)           => loop(child)
          case Offset(_, child)          => loop(child)
          case OrderBy(_, child)         => loop(child)
          case Introspect(_, child)      => loop(child)
          case Environment(_, child)     => loop(child)
          case Component(_, _, child)    => loop(child)
          case Effect(_, child)          => loop(child)
          case TransformCursor(_, child) => loop(child)
          case Count(_)                  => Nil
          case Empty                     => Nil
      }

      loop(query)
    }

    def operationWarnings(op: UntypedOperation): List[Problem] = {
      lazy val opLocation = op match {
        case _: UntypedQuery => Ast.DirectiveLocation.QUERY
        case _: UntypedMutation => Ast.DirectiveLocation.MUTATION
        case _: UntypedSubscription => Ast.DirectiveLocation.SUBSCRIPTION
      }

      val varWarnings = op.variables.flatMap(v => validateDirectives(schema, Ast.DirectiveLocation.VARIABLE_DEFINITION, v.directives, vars))
      val opWarnings = validateDirectives(schema, opLocation, op.directives, vars)
      val childWarnings = queryWarnings(op.query)
      varWarnings ++ opWarnings ++ childWarnings
    }

    def fragmentWarnings(frag: UntypedFragment): List[Problem] = {
      val defnWarnings = validateDirectives(schema, Ast.DirectiveLocation.FRAGMENT_DEFINITION, frag.directives, vars)
      val childWarnings = queryWarnings(frag.child)
      defnWarnings ++ childWarnings
    }

    val opWarnings = operationWarnings(op)
    val fragWarnings = frags.flatMap(fragmentWarnings)

    Result.fromProblems(opWarnings ++ fragWarnings)
  }

  def validateDirectiveOccurrences(schema: Schema, location: Ast.DirectiveLocation, directives: List[Directive]): List[Problem] = {
    val (locationProblems, repetitionProblems) =
      directives.foldLeft((List.empty[Problem], List.empty[Problem])) { case ((locs, reps), directive) =>
        val nme = directive.name
        schema.directives.find(_.name == nme) match {
          case None => (Problem(s"Undefined directive '$nme'") :: locs, reps)
          case Some(defn) =>
            val locs0 =
              if (defn.locations.exists(_ == location)) locs
              else Problem(s"Directive '$nme' is not allowed on $location") :: locs

            val reps0 =
              if (!defn.isRepeatable && directives.count(_.name == nme) > 1)
                Problem(s"Directive '$nme' may not occur more than once") :: reps
              else reps

            (locs0, reps0)
        }
      }

    locationProblems.reverse ++ repetitionProblems.reverse.distinct
  }

  def validateDirectives(schema: Schema, location: Ast.DirectiveLocation, directives: List[Directive], vars: Vars): List[Problem] = {
    val occurrenceProblems = validateDirectiveOccurrences(schema, location, directives)
    val argProblems =
      directives.flatMap { directive =>
        val nme = directive.name
        schema.directives.find(_.name == nme) match {
          case None => List(Problem(s"Undefined directive '$nme'"))
          case Some(defn) =>
            val infos = defn.args
            val unknownArgs = directive.args.filterNot(arg => infos.exists(_.name == arg.name))
            if (unknownArgs.nonEmpty)
              List(Problem(s"Unknown argument(s) ${unknownArgs.map(s => s"'${s.name}'").mkString("", ", ", "")} in directive $nme"))
            else {
              val argMap = directive.args.groupMapReduce(_.name)(_.value)((x, _) => x)
              infos.traverse { info =>
                for {
                  value <- argMap.get(info.name).traverse(Value.elaborateValue(_, vars))
                  _     <- checkValue(info, value, s"directive ${defn.name}")
                } yield ()
              }.toProblems.toList
            }
        }
      }

    occurrenceProblems ++ argProblems
  }

  def elaborateDirectives(schema: Schema, directives: List[Directive], vars: Vars): Result[List[Directive]] =
    directives.traverse { directive =>
      val nme = directive.name
      schema.directives.find(_.name == nme) match {
        case None => Result.failure(s"Undefined directive '$nme'")
        case Some(defn) =>
          val argMap = directive.args.groupMapReduce(_.name)(_.value)((x, _) => x)
          defn.args.traverse { info =>
            for {
              value0 <- argMap.get(info.name).traverse(Value.elaborateValue(_, vars))
              value1 <- checkValue(info, value0, s"directive ${defn.name}")
            } yield Binding(info.name, value1)
          }.map(eArgs => directive.copy(args = eArgs))
      }
    }
}

/**
 * GraphQL schema parser
 */
object SchemaParser {

  import Ast.{Directive => _, EnumValueDefinition => _, Type => _, Value => _, _}

  /**
   * Parse a query String to a query algebra term.
   *
   * Yields a Query value on the right and accumulates errors on the left.
   */
  def parseText(text: String)(implicit pos: SourcePos): Result[Schema] =
    for {
      doc <- GraphQLParser.toResult(text, GraphQLParser.Document.parseAll(text))
      query <- parseDocument(doc)
    } yield query

  def parseDocument(doc: Document)(implicit sourcePos: SourcePos): Result[Schema] = {
    object schema extends Schema {
      var types: List[NamedType] = Nil
      var schemaType1: Option[NamedType] = null
      var pos: SourcePos = sourcePos

      override def schemaType: NamedType = schemaType1.getOrElse(super.schemaType)

      var directives: List[DirectiveDef] = Nil

      def complete(types0: List[NamedType], schemaType0: Option[NamedType], directives0: List[DirectiveDef]): Unit = {
        types = types0
        schemaType1 = schemaType0
        directives = directives0 ++ DirectiveDef.builtIns
      }
    }

    val typeDefns: List[TypeDefinition] = doc.collect { case tpe: TypeDefinition => tpe }
    val dirDefns: List[DirectiveDefinition] = doc.collect { case dir: DirectiveDefinition => dir }
    for {
      types      <- mkTypeDefs(schema, typeDefns)
      directives <- mkDirectiveDefs(schema, dirDefns)
      schemaType <- mkSchemaType(schema, doc)
      _          =  schema.complete(types, schemaType, directives)
      _          <- Result.fromProblems(SchemaValidator.validateSchema(schema, typeDefns))
    } yield schema
  }

  // explicit Schema type, if any
  def mkSchemaType(schema: Schema, doc: Document): Result[Option[NamedType]] = {
    def mkRootOperationType(rootTpe: RootOperationTypeDefinition): Result[Field] = {
      val RootOperationTypeDefinition(optype, tpe, dirs0) = rootTpe
      for {
        dirs <- dirs0.traverse(mkDirective)
        tpe  <- mkType(schema)(tpe)
        _    <- Result.failure(s"Root operation types must be named types, found '$tpe'").whenA(!tpe.nonNull.isNamed)
      } yield Field(optype.name, None, Nil, tpe, dirs)
    }

    def build(dirs: List[Directive], ops: List[Field]): NamedType = {
      val query = ops.find(_.name == "query").getOrElse(Field("query", None, Nil, defaultQueryType, Nil))
      ObjectType(
        name = "Schema",
        description = None,
        fields = query :: List(ops.find(_.name == "mutation"), ops.find(_.name == "subscription")).flatten,
        interfaces = Nil,
        directives = dirs
      )
    }

    def defaultQueryType = schema.ref("Query")

    val defns = doc.collect { case schema: SchemaDefinition => schema }
    defns match {
      case Nil => None.success
      case SchemaDefinition(rootOpTpes, dirs0) :: Nil =>
        for {
          ops <- rootOpTpes.traverse(mkRootOperationType)
          dirs <- dirs0.traverse(mkDirective)
        } yield Some(build(dirs, ops))

      case _ => Result.failure("At most one schema definition permitted")
    }
  }

  def mkTypeDefs(schema: Schema, defns: List[TypeDefinition]): Result[List[NamedType]] =
    defns.traverse(mkTypeDef(schema))

  def mkTypeDef(schema: Schema)(td: TypeDefinition): Result[NamedType] = td match {
    case ScalarTypeDefinition(Name("Int"), _, _) => IntType.success
    case ScalarTypeDefinition(Name("Float"), _, _) => FloatType.success
    case ScalarTypeDefinition(Name("String"), _, _) => StringType.success
    case ScalarTypeDefinition(Name("Boolean"), _, _) => BooleanType.success
    case ScalarTypeDefinition(Name("ID"), _, _) => IDType.success
    case ScalarTypeDefinition(Name(nme), desc, dirs0) =>
      for {
        dirs <- dirs0.traverse(mkDirective)
      } yield ScalarType(nme, desc, dirs)
    case ObjectTypeDefinition(Name(nme), desc, fields0, ifs0, dirs0) =>
      if (fields0.isEmpty) Result.failure(s"object type $nme must define at least one field")
      else
        for {
          fields <- fields0.traverse(mkField(schema))
          ifs    =  ifs0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
          dirs   <- dirs0.traverse(mkDirective)
        } yield ObjectType(nme, desc, fields, ifs, dirs)
    case InterfaceTypeDefinition(Name(nme), desc, fields0, ifs0, dirs0) =>
      if (fields0.isEmpty) Result.failure(s"interface type $nme must define at least one field")
      else
        for {
          fields <- fields0.traverse(mkField(schema))
          ifs    =  ifs0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
          dirs   <- dirs0.traverse(mkDirective)
        } yield InterfaceType(nme, desc, fields, ifs, dirs)
    case UnionTypeDefinition(Name(nme), desc, dirs0, members0) =>
      if (members0.isEmpty) Result.failure(s"union type $nme must define at least one member")
      else {
        for {
          dirs    <- dirs0.traverse(mkDirective)
          members =  members0.map { case Ast.Type.Named(Name(nme)) => schema.ref(nme) }
        } yield UnionType(nme, desc, members, dirs)
      }
    case EnumTypeDefinition(Name(nme), desc, dirs0, values0) =>
      if (values0.isEmpty) Result.failure(s"enum type $nme must define at least one enum value")
      else
        for {
          values <- values0.traverse(mkEnumValue)
          dirs   <- dirs0.traverse(mkDirective)
        } yield EnumType(nme, desc, values, dirs)
    case InputObjectTypeDefinition(Name(nme), desc, fields0, dirs0) =>
      if (fields0.isEmpty) Result.failure(s"input object type $nme must define at least one input field")
      else
        for {
          fields <- fields0.traverse(mkInputValue(schema))
          dirs   <- dirs0.traverse(mkDirective)
        } yield InputObjectType(nme, desc, fields, dirs)
  }

  def mkDirective(d: Ast.Directive): Result[Directive] = {
    val Ast.Directive(Name(nme), args) = d
    args.traverse {
      case (Name(nme), value) => parseValue(value).map(Binding(nme, _))
    }.map(Directive(nme, _))
  }

  def mkField(schema: Schema)(f: FieldDefinition): Result[Field] = {
    val FieldDefinition(Name(nme), desc, args0, tpe0, dirs0) = f
    for {
      args <- args0.traverse(mkInputValue(schema))
      tpe  <- mkType(schema)(tpe0)
      dirs <- dirs0.traverse(mkDirective)
    } yield Field(nme, desc, args, tpe, dirs)
  }

  def mkType(schema: Schema)(tpe: Ast.Type): Result[Type] = {
    def loop(tpe: Ast.Type, nullable: Boolean): Result[Type] = {
      def wrap(tpe: Type): Type = if (nullable) NullableType(tpe) else tpe

      tpe match {
        case Ast.Type.List(tpe) => loop(tpe, true).map(tpe => wrap(ListType(tpe)))
        case Ast.Type.NonNull(Left(tpe)) => loop(tpe, false)
        case Ast.Type.NonNull(Right(tpe)) => loop(tpe, false)
        case Ast.Type.Named(Name(nme)) => wrap(ScalarType.builtIn(nme).getOrElse(schema.ref(nme))).success
      }
    }

    loop(tpe, true)
  }

  def mkDirectiveDefs(schema: Schema, defns: List[DirectiveDefinition]): Result[List[DirectiveDef]] =
    defns.traverse(mkDirectiveDef(schema))

  def mkDirectiveDef(schema: Schema)(dd: DirectiveDefinition): Result[DirectiveDef] = {
    val DirectiveDefinition(Name(nme), desc, args0, repeatable, locations) = dd
    for {
      args <- args0.traverse(mkInputValue(schema))
    } yield DirectiveDef(nme, desc, args, repeatable, locations)
  }

  def mkInputValue(schema: Schema)(f: InputValueDefinition): Result[InputValue] = {
    val InputValueDefinition(Name(nme), desc, tpe0, default0, dirs0) = f
    for {
      tpe <- mkType(schema)(tpe0)
      dflt <- default0.traverse(parseValue)
      dirs <- dirs0.traverse(mkDirective)
    } yield InputValue(nme, desc, tpe, dflt, dirs)
  }

  def mkEnumValue(e: Ast.EnumValueDefinition): Result[EnumValueDefinition] = {
    val Ast.EnumValueDefinition(Name(nme), desc, dirs0) = e
    for {
      dirs <- dirs0.traverse(mkDirective)
    } yield EnumValueDefinition(nme, desc, dirs)
  }

  def parseValue(value: Ast.Value): Result[Value] = {
    value match {
      case Ast.Value.IntValue(i) => IntValue(i).success
      case Ast.Value.FloatValue(d) => FloatValue(d).success
      case Ast.Value.StringValue(s) => StringValue(s).success
      case Ast.Value.BooleanValue(b) => BooleanValue(b).success
      case Ast.Value.EnumValue(e) => EnumValue(e.value).success
      case Ast.Value.Variable(v) => VariableRef(v.value).success
      case Ast.Value.NullValue => NullValue.success
      case Ast.Value.ListValue(vs) => vs.traverse(parseValue).map(ListValue(_))
      case Ast.Value.ObjectValue(fs) =>
        fs.traverse { case (name, value) =>
          parseValue(value).map(v => (name.value, v))
        }.map(ObjectValue(_))
    }
  }
}

object SchemaValidator {
  import SchemaRenderer.renderType

  def validateSchema(schema: Schema, defns: List[TypeDefinition]): List[Problem] =
    validateReferences(schema, defns) ++
    validateUniqueDefns(schema) ++
    validateUniqueEnumValues(schema) ++
    validateImplementations(schema) ++
    Directive.validateDirectivesForSchema(schema)

  def validateReferences(schema: Schema, defns: List[TypeDefinition]): List[Problem] = {
    def underlyingName(tpe: Ast.Type): String =
      tpe match {
        case Ast.Type.List(tpe) => underlyingName(tpe)
        case Ast.Type.NonNull(Left(tpe)) => underlyingName(tpe)
        case Ast.Type.NonNull(Right(tpe)) => underlyingName(tpe)
        case Ast.Type.Named(Ast.Name(nme)) => nme
      }

    def referencedTypes(defns: List[TypeDefinition]): List[String] = {
      defns.flatMap {
        case ObjectTypeDefinition(_, _, fields, interfaces, _) =>
          (fields.flatMap(_.args.map(_.tpe)) ++ fields.map(_.tpe) ++ interfaces).map(underlyingName)
        case InterfaceTypeDefinition(_, _, fields, interfaces, _) =>
          (fields.flatMap(_.args.map(_.tpe)) ++ fields.map(_.tpe) ++ interfaces).map(underlyingName)
        case u: UnionTypeDefinition =>
          u.members.map(underlyingName)
        case _ => Nil
      }
    }

    val defaultTypes = List(StringType, IntType, FloatType, BooleanType, IDType)
    val typeNames = (defaultTypes ++ schema.types).map(_.name).toSet

    referencedTypes(defns).collect {
      case tpe if !typeNames.contains(tpe) => Problem(s"Reference to undefined type '$tpe'")
    }
  }

  def validateUniqueDefns(schema: Schema): List[Problem] = {
    val dupes = schema.types.groupBy(_.name).collect {
      case (nme, tpes) if tpes.length > 1 => nme
    }.toSet

    schema.types.map(_.name).distinct.collect {
      case nme if dupes.contains(nme) => Problem(s"Duplicate definition of type '$nme' found")
    }
  }

  def validateUniqueEnumValues(schema: Schema): List[Problem] = {
    val enums = schema.types.collect {
      case e: EnumType => e
    }

    enums.flatMap { e =>
      val duplicateValues = e.enumValues.groupBy(_.name).collect { case (nme, vs) if vs.length > 1 => nme }.toList
      duplicateValues.map(dupe => Problem(s"Duplicate definition of enum value '$dupe' for Enum type '${e.name}'"))
    }
  }

  def validateImplementations(schema: Schema): List[Problem] = {

    def validateImplementor(impl: TypeWithFields): List[Problem] = {
      import impl.{name, fields, interfaces}

      interfaces.flatMap(_.dealias match {
        case iface: InterfaceType =>
          iface.fields.flatMap { ifField =>
            fields.find(_.name == ifField.name).map { implField =>
              val ifTpe = ifField.tpe
              val implTpe = implField.tpe

              val rp =
                if (implTpe <:< ifTpe) Nil
                else List(Problem(s"Field '${implField.name}' of type '$name' has type '${renderType(implTpe)}', however implemented interface '${iface.name}' requires it to be a subtype of '${renderType(ifTpe)}'"))

              val argsMatch =
                implField.args.corresponds(ifField.args) { case (arg0, arg1) =>
                  arg0.name == arg1.name && arg0.tpe == arg1.tpe
                }

              val ap =
                if (argsMatch) Nil
                else List(Problem(s"Field '${implField.name}' of type '$name' has has an argument list that does not conform to that specified by implemented interface '${iface.name}'"))

              rp ++ ap
            }.getOrElse(List(Problem(s"Field '${ifField.name}' from interface '${iface.name}' is not defined by implementing type '$name'")))
          }
        case other =>
          List(Problem(s"Non-interface type '${other.name}' declared as implemented by type '$name'"))
      })
    }

    val impls = schema.types.collect { case impl: TypeWithFields => impl }
    impls.flatMap(validateImplementor)
  }
}

object SchemaRenderer {
  def renderSchema(schema: Schema): String = {
    def mkRootDef(fieldName: String)(tpe: NamedType): String =
      s"$fieldName: ${tpe.name}"

    val fields =
      mkRootDef("query")(schema.queryType) ::
        List(
          schema.mutationType.map(mkRootDef("mutation")),
          schema.subscriptionType.map(mkRootDef("subscription"))
        ).flatten

    val schemaDefn = {
      val dirs0 = schema.schemaType.directives
      if (fields.sizeCompare(1) == 0 && schema.queryType =:= schema.ref("Query") && dirs0.isEmpty) ""
      else {
        val dirs = renderDirectives(dirs0)
        fields.mkString(s"schema$dirs {\n  ", "\n  ", "\n}\n")
      }
    }

    val dirDefns = {
      val nonBuiltInDefns =
        schema.directives.filter {
          case DirectiveDef("skip"|"include"|"deprecated", _, _, _, _) => false
          case _ => true
        }

      if(nonBuiltInDefns.isEmpty) ""
      else "\n"+nonBuiltInDefns.map(renderDirectiveDefn).mkString("\n")+"\n"
    }

    schemaDefn ++
      schema.types.map(renderTypeDefn).mkString("\n") ++
      dirDefns
  }

  def renderDescription(desc: Option[String]): String =
    desc match {
      case None => ""
      case Some(desc) => s""""$desc"\n"""
    }

  def renderDirectives(dirs: List[Directive]): String =
    if (dirs.isEmpty) "" else dirs.map(renderDirective).mkString(" ", " ", "")

  def renderDirective(d: Directive): String = {
    val Directive(name, args0) = d
    val args = if(args0.isEmpty) "" else args0.map { case Binding(nme, v) => s"$nme: ${renderValue(v)}" }.mkString("(", ", ", ")")
    s"@$name$args"
  }

  def renderTypeDefn(tpe: NamedType): String = {
    def renderField(f: Field): String = {
      val Field(nme, _, args, tpe, dirs0) = f
      val dirs = renderDirectives(dirs0)
      if (args.isEmpty)
        s"$nme: ${renderType(tpe)}$dirs"
      else
        s"$nme(${args.map(renderInputValue).mkString(", ")}): ${renderType(tpe)}$dirs"
    }

    tpe match {
      case tr: TypeRef => renderTypeDefn(tr.dealias)

      case ScalarType(nme, _, dirs0) =>
        val dirs = renderDirectives(dirs0)
        s"""scalar $nme$dirs"""

      case ObjectType(nme, _, fields, ifs0, dirs0) =>
        val ifs = if (ifs0.isEmpty) "" else " implements " + ifs0.map(_.name).mkString("&")
        val dirs = renderDirectives(dirs0)

        s"""|type $nme$ifs$dirs {
            |  ${fields.map(renderField).mkString("\n  ")}
            |}""".stripMargin

      case InterfaceType(nme, _, fields, ifs0, dirs0) =>
        val ifs = if (ifs0.isEmpty) "" else " implements " + ifs0.map(_.name).mkString("&")
        val dirs = renderDirectives(dirs0)

        s"""|interface $nme$ifs$dirs {
            |  ${fields.map(renderField).mkString("\n  ")}
            |}""".stripMargin

      case UnionType(nme, _, members, dirs0) =>
        val dirs = renderDirectives(dirs0)
        s"""union $nme$dirs = ${members.map(_.name).mkString(" | ")}"""

      case EnumType(nme, _, values, dirs0) =>
        val dirs = renderDirectives(dirs0)
        s"""|enum $nme$dirs {
            |  ${values.map(renderEnumValueDefinition).mkString("\n  ")}
            |}""".stripMargin

      case InputObjectType(nme, _, fields, dirs0) =>
        val dirs = renderDirectives(dirs0)
        s"""|input $nme$dirs {
            |  ${fields.map(renderInputValue).mkString("\n  ")}
            |}""".stripMargin
    }
  }

  def renderType(tpe: Type): String = {
    def loop(tpe: Type, nullable: Boolean): String = {
      def wrap(tpe: String) = if (nullable) tpe else s"$tpe!"

      tpe match {
        case NullableType(tpe) => loop(tpe, true)
        case ListType(tpe) => wrap(s"[${loop(tpe, false)}]")
        case nt: NamedType => wrap(nt.name)
      }
    }

    loop(tpe, false)
  }

  def renderDirectiveDefn(directive: DirectiveDef): String = {
     val DirectiveDef(nme, desc, args, repeatable, locations) = directive
     val rpt = if (repeatable) " repeatable" else ""
     if (args.isEmpty)
       s"${renderDescription(desc)}directive @$nme$rpt on ${locations.mkString("|")}"
     else
       s"${renderDescription(desc)}directive @$nme(${args.map(renderInputValue).mkString(", ")})$rpt on ${locations.mkString("|")}"
  }

  def renderEnumValueDefinition(v: EnumValueDefinition): String = {
    val EnumValueDefinition(nme, _, dirs0) = v
    val dirs = renderDirectives(dirs0)
    s"$nme$dirs"
  }

  def renderInputValue(iv: InputValue): String = {
    val InputValue(nme, _, tpe, default, dirs0) = iv
    val dirs = renderDirectives(dirs0)
    val df = default.map(v => s" = ${renderValue(v)}").getOrElse("")
    s"$nme: ${renderType(tpe)}$df$dirs"
  }

  def renderValue(value: Value): String = value match {
    case IntValue(i) => i.toString
    case FloatValue(f) => f.toString
    case StringValue(s) => s""""$s""""
    case BooleanValue(b) => b.toString
    case IDValue(i) => s""""$i""""
    case EnumValue(e) => e
    case ListValue(elems) => elems.map(renderValue).mkString("[", ", ", "]")
    case ObjectValue(fields) =>
      fields.map {
        case (name, value) => s"$name : ${renderValue(value)}"
      }.mkString("{", ", ", "}")
    case _ => "null"
  }
}
