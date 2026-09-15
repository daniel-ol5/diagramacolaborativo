// Generado por ModelCollab desde el diagrama "Barberia".
//
// Saca los DATOS de lo que quedo del dictado, segun el tipo de cada ranura
// (§10.3). Todo local: son expresiones regulares y tablas, no un modelo.
//
// NOTA PARA QUIEN EDITE ESTA PLANTILLA: de aca para abajo va todo dentro de un
// bloque que FreeMarker no interpreta, porque Dart y FreeMarker usan la MISMA
// sintaxis de interpolacion. Sin ese bloque, FreeMarker intenta resolver cada
// interpolacion de Dart como si fuera una variable suya: la generacion falla, o
// peor, la resuelve a vacio y produce codigo que compila y hace cualquier cosa.
//
// Y el nombre de la directiva NO se escribe en este comentario a proposito:
// nombrarla la ACTIVA aca mismo, el bloque arranca diez lineas antes de donde
// uno cree, y el marcador de abajo sale impreso como texto en el .dart. Ya paso.
import 'catalogo.dart';
import 'clasificador.dart';

class Extractor {
  const Extractor();

  /// Devuelve nombre de ranura -> valor, ya en el formato que espera la API.
  ///
  /// Las ranuras que no se encuentran NO se inventan: quedan afuera, y quien
  /// llama decide si preguntar por las obligatorias que faltan. Rellenar con
  /// un valor por defecto seria guardar un dato que el usuario nunca dijo.
  Map<String, String> extraer(Reconocimiento reconocido) {
    final String texto = Clasificador.normalizar(reconocido.resto);
    final Map<String, String> valores = <String, String>{};

    for (final Ranura ranura in reconocido.intencion.ranuras) {
      final String? valor = _porTipo(ranura, texto);
      if (valor != null && valor.isNotEmpty) {
        valores[ranura.nombre] = valor;
      }
    }
    return valores;
  }

  String? _porTipo(Ranura ranura, String texto) {
    if (ranura.referencia != null) {
      // "con Carlos", "de Juan": el nombre del que habla la relacion. Queda
      // como texto; quien llama lo resuelve contra la entidad referenciada.
      return _trasPreposicion(texto);
    }
    switch (ranura.tipo) {
      case 'DATE':
        return _fecha(texto);
      case 'DATETIME':
        return _fechaConHora(texto);
      case 'INTEGER':
      case 'BIGINT':
        return _entero(texto);
      case 'DECIMAL':
        return _decimal(texto);
      case 'BOOLEAN':
        return _booleano(texto);
      case 'VARCHAR':
      case 'TEXT':
        return _trasPreposicion(texto);
      default:
        // UUID entre otros: nadie dicta un identificador.
        return null;
    }
  }

  // ------------------------------------------------------------- fechas

  static const Map<String, int> _diasDeLaSemana = <String, int>{
    'lunes': DateTime.monday,
    'martes': DateTime.tuesday,
    'miercoles': DateTime.wednesday,
    'jueves': DateTime.thursday,
    'viernes': DateTime.friday,
    'sabado': DateTime.saturday,
    'domingo': DateTime.sunday,
  };

  static const List<String> _meses = <String>[
    'enero',
    'febrero',
    'marzo',
    'abril',
    'mayo',
    'junio',
    'julio',
    'agosto',
    'septiembre',
    'octubre',
    'noviembre',
    'diciembre',
  ];

  /// Resuelve "hoy", "manana", "pasado manana" y "el lunes" a una fecha real.
  ///
  /// Se resuelve ACA y no en el servidor a proposito: la app tiene que
  /// funcionar en modo avion, y "manana" depende del reloj del telefono.
  String? _fecha(String texto) {
    final DateTime hoy = DateTime.now();

    if (texto.contains('pasado manana')) {
      return _iso(hoy.add(const Duration(days: 2)));
    }
    if (texto.contains('manana')) {
      return _iso(hoy.add(const Duration(days: 1)));
    }
    if (texto.contains('hoy')) {
      return _iso(hoy);
    }
    for (final MapEntry<String, int> dia in _diasDeLaSemana.entries) {
      if (texto.contains(dia.key)) {
        // El PROXIMO dia con ese nombre. Si hoy es miercoles y se dice "el
        // miercoles", se entiende el de la semana que viene: nadie agenda algo
        // para hoy diciendo el nombre del dia.
        int faltan = (dia.value - hoy.weekday + 7) % 7;
        if (faltan == 0) {
          faltan = 7;
        }
        return _iso(hoy.add(Duration(days: faltan)));
      }
    }
    final RegExpMatch? explicita =
        RegExp(r'(\d{1,2})\s+de\s+([a-z]+)').firstMatch(texto);
    if (explicita != null) {
      final int mes = _meses.indexOf(explicita.group(2)!) + 1;
      if (mes > 0) {
        final int dia = int.parse(explicita.group(1)!);
        return _iso(DateTime(hoy.year, mes, dia));
      }
    }
    return null;
  }

  /// "manana a las 10", "a las 15:30", "10 de la noche".
  String? _fechaConHora(String texto) {
    final String fecha = _fecha(texto) ?? _iso(DateTime.now());
    final RegExpMatch? reloj = RegExp(
      r'(\d{1,2})[:\s]?(\d{2})?\s*(am|pm|de la manana|de la tarde|de la noche)?',
    ).firstMatch(_trasLasHoras(texto));

    if (reloj == null) {
      // Sin hora dictada se asume el comienzo de la jornada, y la pantalla lo
      // muestra antes de guardar para que se pueda corregir.
      return '${fecha}T09:00:00';
    }

    int hora = int.parse(reloj.group(1)!);
    final int minutos = int.tryParse(reloj.group(2) ?? '0') ?? 0;
    final String? franja = reloj.group(3);

    // "3 de la tarde" son las 15, no las 3. Sin esto, una cita de la tarde se
    // agenda de madrugada y el error se descubre cuando no llega nadie.
    if (franja != null && hora < 12) {
      if (franja == 'pm' ||
          franja == 'de la tarde' ||
          franja == 'de la noche') {
        hora += 12;
      }
    }
    final String hh = hora.toString().padLeft(2, '0');
    final String mm = minutos.toString().padLeft(2, '0');
    return '${fecha}T$hh:$mm:00';
  }

  /// Lo que viene despues de "a las", que es donde esta la hora.
  String _trasLasHoras(String texto) {
    final int marca = texto.indexOf('a las ');
    return marca < 0 ? texto : texto.substring(marca + 'a las '.length);
  }

  String _iso(DateTime fecha) {
    final String mes = fecha.month.toString().padLeft(2, '0');
    final String dia = fecha.day.toString().padLeft(2, '0');
    return '${fecha.year}-$mes-$dia';
  }

  // ------------------------------------------------------------ numeros

  static const Map<String, int> _numerosEnPalabras = <String, int>{
    'uno': 1,
    'una': 1,
    'dos': 2,
    'tres': 3,
    'cuatro': 4,
    'cinco': 5,
    'seis': 6,
    'siete': 7,
    'ocho': 8,
    'nueve': 9,
    'diez': 10,
    'once': 11,
    'doce': 12,
    'veinte': 20,
    'treinta': 30,
    'cien': 100,
  };

  String? _entero(String texto) {
    final RegExpMatch? digitos = RegExp(r'\b(\d+)\b').firstMatch(texto);
    if (digitos != null) {
      return digitos.group(1);
    }
    for (final MapEntry<String, int> palabra in _numerosEnPalabras.entries) {
      if (texto.contains(palabra.key)) {
        return palabra.value.toString();
      }
    }
    return null;
  }

  String? _decimal(String texto) {
    final RegExpMatch? conComa = RegExp(
      r'\b(\d+)\s*(?:con|punto|coma)\s*(\d+)\b',
    ).firstMatch(texto);
    if (conComa != null) {
      return '${conComa.group(1)}.${conComa.group(2)}';
    }
    return _entero(texto);
  }

  String? _booleano(String texto) {
    if (RegExp(r'\b(si|activo|activa|habilitado|verdadero)\b').hasMatch(texto)) {
      return 'true';
    }
    if (RegExp(r'\b(no|inactivo|inactiva|deshabilitado|falso)\b')
        .hasMatch(texto)) {
      return 'false';
    }
    return null;
  }

  // -------------------------------------------------------------- texto

  static final RegExp _preposicion =
      RegExp(r'\b(?:con|para|de|llamado|llamada|nombre)\s+(.+)$');

  /// El texto que sigue a una preposicion: "con Carlos" -> "carlos".
  ///
  /// Es una heuristica y se equivoca: en "crea un cliente con telefono 555"
  /// devuelve "telefono 555". Se prefiere eso a no extraer nada, porque la
  /// pantalla muestra lo que se entendio ANTES de guardar y la persona lo
  /// corrige. Lo que no se hace es inventar cuando no hay nada que sacar.
  String? _trasPreposicion(String texto) {
    final RegExpMatch? coincidencia = _preposicion.firstMatch(texto);
    return coincidencia?.group(1)?.trim();
  }
}
