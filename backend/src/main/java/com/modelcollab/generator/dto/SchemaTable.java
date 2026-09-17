package com.modelcollab.generator.dto;

import java.util.List;

/**
 * Una tabla del esquema físico.
 *
 * <p>Hay dos clases de tabla y el cliente las dibuja distinto: las que vienen de una clase del
 * diagrama y las <b>intermedias</b> de un muchos-a-muchos, que no tienen clase detrás y existen
 * sólo porque una relación las obliga.
 *
 * <p>Ojo con la herencia: la estrategia generada es {@code JOINED}, así que <b>cada clase tiene
 * su propia tabla</b>, también las hijas. La tabla de {@code Dueno} lleva una única columna
 * {@code id} que es clave primaria <i>y</i> clave foránea contra {@code persona}. No es un caso
 * raro: es la veterinaria.
 *
 * @param className nombre de la clase Java generada, o {@code null} si es una tabla intermedia
 * @param name nombre físico <b>sin comillas</b>: {@code order}, no {@code "order"}
 * @param quoted si el nombre es palabra reservada de PostgreSQL y el DDL lo entrecomilla. El
 *     cliente lo puede mostrar como quiera, pero conviene que lo muestre: explica por qué una
 *     tabla se llama {@code order} y hay que escribirla {@code "order"} en cualquier consulta
 * @param joinTable si esta tabla es la intermedia de un muchos-a-muchos
 * @param abstractClass si la clase es abstracta. Tiene tabla igual —JOINED— pero no tiene API:
 *     no se puede crear una {@code Persona} por REST, sólo un {@code Dueno}
 * @param columns todas las columnas, en el orden en que las escribe el generador: clave primaria,
 *     atributos propios, claves foráneas sintetizadas
 */
public record SchemaTable(
        String className,
        String name,
        boolean quoted,
        boolean joinTable,
        boolean abstractClass,
        List<SchemaColumn> columns) {

    public SchemaTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
