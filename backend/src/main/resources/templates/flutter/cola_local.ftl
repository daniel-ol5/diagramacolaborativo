// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// Cola de operaciones pendientes en SQLite (RF-08.4).
//
// El asistente tiene que funcionar en modo avion, asi que lo que se dicta se
// guarda ACA primero y se manda al servidor cuando haya conexion. Nunca al
// reves: si primero se intentara mandar, una orden dictada en el subsuelo de
// un local se perderia, y el usuario no tiene forma de saber cuales se
// perdieron.
<#noparse>
import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../api/cliente_api.dart';

/// Una orden dictada que todavia no llego al servidor.
class Pendiente {
  const Pendiente({
    required this.id,
    required this.entidad,
    required this.accion,
    required this.datos,
    required this.referencias,
    required this.creadoEn,
  });

  final int id;
  final String entidad;
  final String accion;
  final Map<String, dynamic> datos;

  /// Campos cuyo valor es todavia un NOMBRE dictado y no un identificador:
  /// campo -> entidad contra la que hay que resolverlo.
  final Map<String, String> referencias;

  final DateTime creadoEn;
}

class ColaLocal {
  ColaLocal._(this._db);

  final Database _db;

  static const String _tabla = 'pendientes';

  static Future<ColaLocal> abrir({String archivo = 'asistente.db'}) async {
    final String ruta = p.join(await getDatabasesPath(), archivo);
    final Database db = await openDatabase(
      ruta,
      version: 1,
      onCreate: (Database db, int version) async {
        await db.execute('''
          CREATE TABLE $_tabla (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entidad TEXT NOT NULL,
            accion TEXT NOT NULL,
            datos TEXT NOT NULL,
            referencias TEXT NOT NULL,
            creado_en INTEGER NOT NULL
          )
        ''');
      },
    );
    return ColaLocal._(db);
  }

  /// Guarda la orden. Devuelve su id local.
  Future<int> encolar(
    String entidad,
    String accion,
    Map<String, dynamic> datos, {
    Map<String, String> referencias = const <String, String>{},
  }) async {
    return _db.insert(_tabla, <String, Object?>{
      'entidad': entidad,
      'accion': accion,
      'datos': jsonEncode(datos),
      'referencias': jsonEncode(referencias),
      'creado_en': DateTime.now().millisecondsSinceEpoch,
    });
  }

  /// Lo que falta mandar, en el orden en que se dicto.
  ///
  /// El orden importa: si se dicto "crea el cliente Juan" y despues "crea una
  /// cita con Juan", mandarlas al reves deja la segunda sin a quien apuntar.
  Future<List<Pendiente>> pendientes() async {
    final List<Map<String, Object?>> filas =
        await _db.query(_tabla, orderBy: 'creado_en ASC, id ASC');

    return filas
        .map(
          (Map<String, Object?> fila) => Pendiente(
            id: fila['id']! as int,
            entidad: fila['entidad']! as String,
            accion: fila['accion']! as String,
            datos: jsonDecode(fila['datos']! as String) as Map<String, dynamic>,
            referencias:
                (jsonDecode(fila['referencias']! as String) as Map<String, dynamic>)
                    .map(
                      (String clave, dynamic valor) =>
                          MapEntry<String, String>(clave, valor as String),
                    ),
            creadoEn: DateTime.fromMillisecondsSinceEpoch(
              fila['creado_en']! as int,
            ),
          ),
        )
        .toList(growable: false);
  }

  Future<void> quitar(int id) async {
    await _db.delete(_tabla, where: 'id = ?', whereArgs: <Object?>[id]);
  }

  /// Manda lo pendiente y devuelve cuantas salieron.
  ///
  /// Se corta en el PRIMER fallo en vez de seguir con las demas. Dos razones:
  /// si fallo por falta de conexion, las siguientes van a fallar igual y no
  /// tiene sentido intentarlo; y si fallo por otra cosa, seguir adelante
  /// rompe el orden — la que viene despues puede depender de la que fallo.
  Future<int> sincronizar(ClienteApi api) async {
    int enviadas = 0;
    for (final Pendiente pendiente in await pendientes()) {
      try {
        switch (pendiente.accion) {
          case 'CREATE':
            final Map<String, dynamic>? datos = await _resolver(api, pendiente);
            if (datos == null) {
              // No se pudo resolver un nombre dictado contra una entidad que
              // existe. Se CORTA en vez de descartar: mandar la peticion sin
              // ese campo la haria rechazar por el servidor, y descartarla
              // perderia una orden que la persona dio. Queda para el proximo
              // intento, cuando quiza ese cliente ya exista.
              return enviadas;
            }
            await api.crear(pendiente.entidad, datos);
          case 'DELETE':
            final Object? id = pendiente.datos['id'];
            if (id == null) {
              // Sin id no se puede borrar nada. Se descarta en vez de
              // reintentarla para siempre: una pendiente que no puede tener
              // exito nunca bloquea a todas las que vienen detras.
              await quitar(pendiente.id);
              continue;
            }
            await api.eliminar(pendiente.entidad, id.toString());
          default:
            // LIST y SEARCH no cambian nada del servidor: no se encolan.
            await quitar(pendiente.id);
            continue;
        }
        await quitar(pendiente.id);
        enviadas++;
      } on Exception {
        break;
      }
    }
    return enviadas;
  }

  /// Cambia los nombres dictados por los identificadores que espera la API.
  ///
  /// Se hace ACA y no al confirmar, a proposito: al confirmar puede no haber
  /// conexion, y resolver necesita preguntarle al servidor. Haciendolo al
  /// sincronizar, el circuito es el mismo con red y sin red — que es
  /// justamente lo que la app promete.
  ///
  /// Devuelve null si alguna referencia no se pudo resolver.
  Future<Map<String, dynamic>?> _resolver(
    ClienteApi api,
    Pendiente pendiente,
  ) async {
    if (pendiente.referencias.isEmpty) {
      return pendiente.datos;
    }
    final Map<String, dynamic> datos = Map<String, dynamic>.of(pendiente.datos);

    for (final MapEntry<String, String> referencia
        in pendiente.referencias.entries) {
      final Object? dictado = datos[referencia.key];
      if (dictado == null) {
        continue;
      }
      final String? id = await api.resolverId(
        referencia.value,
        dictado.toString(),
      );
      if (id == null) {
        return null;
      }
      datos[referencia.key] = id;
    }
    return datos;
  }

  Future<void> cerrar() => _db.close();
}
</#noparse>
