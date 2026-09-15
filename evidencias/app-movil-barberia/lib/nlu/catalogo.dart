// Generado por ModelCollab desde el diagrama "Barberia".
//
// Lee assets/intents.json, que es el catalogo derivado del metamodelo.
//
// El catalogo viaja como DATO y no como codigo a proposito: es lo unico que
// cambia cuando cambia el diagrama, asi que regenerarlo no obliga a tocar
// ninguna de estas clases.
import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

/// Una ranura: el pedazo de la frase dictada que hay que extraer.
class Ranura {
  const Ranura({
    required this.nombre,
    required this.tipo,
    required this.obligatoria,
    this.referencia,
  });

  final String nombre;

  /// Tipo del metamodelo: dice que analizador local usar.
  final String tipo;

  final bool obligatoria;

  /// Nombre de otra entidad cuando la ranura es una referencia y no un dato
  /// suelto. "con Carlos" no es un texto: es un Cliente que hay que buscar.
  final String? referencia;

  factory Ranura.desdeJson(Map<String, dynamic> json) {
    return Ranura(
      nombre: json['name'] as String,
      tipo: json['type'] as String,
      // El backend omite los campos nulos, asi que "required" puede no venir.
      obligatoria: json['required'] as bool? ?? false,
      referencia: json['references'] as String?,
    );
  }
}

/// Una intencion: que quiere hacer el usuario, sobre que entidad.
class Intencion {
  const Intencion({
    required this.nombre,
    required this.entidad,
    required this.accion,
    required this.frases,
    required this.ranuras,
  });

  final String nombre;
  final String entidad;

  /// CREATE, LIST, SEARCH o DELETE.
  final String accion;

  /// Las frases que la disparan. Son varias porque el reconocedor devuelve lo
  /// que entendio, no lo que se dijo.
  final List<String> frases;

  final List<Ranura> ranuras;

  factory Intencion.desdeJson(Map<String, dynamic> json) {
    return Intencion(
      nombre: json['name'] as String,
      entidad: json['entity'] as String,
      accion: json['action'] as String,
      frases: (json['triggers'] as List<dynamic>? ?? <dynamic>[])
          .map((dynamic f) => f as String)
          .toList(growable: false),
      ranuras: (json['slots'] as List<dynamic>? ?? <dynamic>[])
          .map((dynamic r) => Ranura.desdeJson(r as Map<String, dynamic>))
          .toList(growable: false),
    );
  }
}

/// El catalogo completo.
class Catalogo {
  const Catalogo({required this.idioma, required this.intenciones});

  /// "es" a secas, sin dialecto: la preferencia se elige por modelo instalado.
  final String idioma;

  final List<Intencion> intenciones;

  static Future<Catalogo> cargar() async {
    final String texto = await rootBundle.loadString('assets/intents.json');
    final Map<String, dynamic> json = jsonDecode(texto) as Map<String, dynamic>;
    return Catalogo(
      idioma: json['language'] as String? ?? 'es',
      intenciones: (json['intents'] as List<dynamic>? ?? <dynamic>[])
          .map((dynamic i) => Intencion.desdeJson(i as Map<String, dynamic>))
          .toList(growable: false),
    );
  }
}
