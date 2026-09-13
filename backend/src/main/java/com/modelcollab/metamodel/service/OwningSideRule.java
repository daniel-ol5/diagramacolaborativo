package com.modelcollab.metamodel.service;

import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;

/**
 * La regla de {@code owningSide} de <b>D-08</b>, en un único lugar.
 *
 * <h2>Por qué existe este archivo</h2>
 *
 * <p>La regla estaba escrita dos veces: el validador la aplicaba para avisar, y el generador
 * <b>no la aplicaba</b> — leía {@code r.owningSide()} y le creía. El aviso del validador decía,
 * con todas las letras, <i>"El generador va a usar TARGET"</i>, y el generador usaba SOURCE.
 *
 * <p>Eso no quedaba en una incoherencia de documentación. Con {@code 1..1 → 1..*} y
 * {@code owningSide=SOURCE}, el lado de la colección salía anotado
 * {@code @ManyToOne(mappedBy = "...")}, y <b>{@code mappedBy} no existe en {@code @ManyToOne}</b>:
 * el ZIP no compilaba. Es exactamente el riesgo R-04 —"el código generado no compila en la
 * defensa en vivo"— disparado por el campo que D-08 declara no confiable.
 *
 * <p>Con una sola implementación, el aviso y el comportamiento no se pueden volver a separar.
 *
 * <h2>La regla</h2>
 *
 * <p>{@code owningSide} es <b>autoritativo sólo cuando no es derivable</b>. Cuando las
 * multiplicidades determinan dónde tiene que ir la clave foránea, mandan ellas y el campo se
 * ignora — porque un diagrama puede traerlo mal, y de hecho lo trae mal cada vez que nadie lo
 * tocó: el valor por defecto del schema de §7 es {@code SOURCE}, que acierta la mitad de las
 * veces por casualidad.
 */
public final class OwningSideRule {

    private OwningSideRule() {}

    /**
     * Si las multiplicidades <b>no</b> alcanzan para decidir, y por lo tanto manda lo que declara
     * el diagrama.
     *
     * <p>Son dos casos. En {@code MANY_TO_MANY} la clave foránea no está en ninguna de las dos
     * tablas sino en la intermedia, así que el lado dueño es una elección genuina del modelador:
     * dice de qué lado se escribe la {@code @JoinTable}. Y en una relación escalar simétrica
     * ({@code 1..1 ↔ 1..1}) las dos tablas pueden llevar la columna igual de bien.
     */
    public static boolean esDerivable(Relationship r) {
        if (r.type() == RelationshipType.MANY_TO_MANY) {
            return false;
        }
        Multiplicity sm = r.sourceMultiplicity();
        Multiplicity tm = r.targetMultiplicity();
        boolean hayColeccion = esColeccion(sm) || esColeccion(tm);
        return hayColeccion || sm != tm;
    }

    /**
     * El lado que de verdad va a llevar la clave foránea.
     *
     * <p>Con colección, la FK va del lado que puede repetirse: si el origen admite varios, la
     * columna vive en su tabla. Sin colección pero con extremos distintos, va en el extremo
     * "flojo", el que no es obligatorio — poner la columna del lado {@code 1..1} obligaría a
     * tener siempre la otra fila.
     *
     * <p>Para herencia y dependencia el resultado no significa nada: ninguna de las dos genera
     * columna. Se devuelve lo declarado para no inventar una respuesta a una pregunta que nadie
     * hizo.
     */
    public static OwningSide efectivo(Relationship r) {
        if (r.type() == RelationshipType.INHERITANCE
                || r.type() == RelationshipType.DEPENDENCY
                || !esDerivable(r)) {
            return r.owningSide();
        }
        Multiplicity sm = r.sourceMultiplicity();
        Multiplicity tm = r.targetMultiplicity();

        if (esColeccion(sm) || esColeccion(tm)) {
            return esColeccion(sm) ? OwningSide.SOURCE : OwningSide.TARGET;
        }
        return sm == Multiplicity.ZERO_ONE ? OwningSide.SOURCE : OwningSide.TARGET;
    }

    private static boolean esColeccion(Multiplicity m) {
        return m != null && m.isCollection();
    }
}
