// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// Cliente REST contra el backend Spring Boot generado del MISMO diagrama.
//
// Las rutas de aca abajo NO estan escritas a mano: salen de la misma regla que
// usa el generador del backend para su @RequestMapping. Si se calcularan por
// separado, un dia dejarian de coincidir y el sintoma no aparecería en ninguno
// de los dos proyectos — apareceria en el telefono de un usuario final, como
// un 404 que no dice nada sobre su causa.
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config.dart';

class ClienteApi {
  ClienteApi({http.Client? cliente}) : _http = cliente ?? http.Client();

  final http.Client _http;

  /// Nombre de entidad del diagrama -> ruta que expone el backend generado.
  static const Map<String, String> rutasPorEntidad = <String, String>{
<#list entidades as entidad>
    '${entidad.nombre}': '/${entidad.ruta}',
</#list>
  };
<#noparse>

  /// Cuanto se espera antes de darse por vencido.
  ///
  /// Corto a proposito: la app tiene que seguir siendo usable sin conexion, y
  /// una espera larga se siente como que se colgo. Si no contesta rapido, la
  /// operacion se guarda local y se sincroniza despues.
  static const Duration _espera = Duration(seconds: 6);

  Uri _uri(String entidad, [String sufijo = '']) {
    final String? ruta = rutasPorEntidad[entidad];
    if (ruta == null) {
      // El catalogo de intenciones y este mapa salen del MISMO diagrama, asi
      // que esto no deberia pasar nunca. Si pasa, es que quedaron
      // desincronizados, y conviene que se note aca y no como un 404.
      throw ArgumentError('La entidad $entidad no esta en este diagrama');
    }
    return Uri.parse(Config.url('$ruta$sufijo'));
  }

  Future<Map<String, dynamic>> crear(
    String entidad,
    Map<String, dynamic> datos,
  ) async {
    final http.Response respuesta = await _http
        .post(
          _uri(entidad),
          headers: const <String, String>{'Content-Type': 'application/json'},
          body: jsonEncode(datos),
        )
        .timeout(_espera);
    return _cuerpo(respuesta);
  }

  Future<List<dynamic>> listar(String entidad) async {
    final http.Response respuesta =
        await _http.get(_uri(entidad)).timeout(_espera);
    final dynamic cuerpo = _decodificar(respuesta);
    return cuerpo is List<dynamic> ? cuerpo : <dynamic>[];
  }

  Future<void> eliminar(String entidad, String id) async {
    final http.Response respuesta =
        await _http.delete(_uri(entidad, '/$id')).timeout(_espera);
    _verificar(respuesta);
  }

  Map<String, dynamic> _cuerpo(http.Response respuesta) {
    final dynamic cuerpo = _decodificar(respuesta);
    return cuerpo is Map<String, dynamic> ? cuerpo : <String, dynamic>{};
  }

  dynamic _decodificar(http.Response respuesta) {
    _verificar(respuesta);
    if (respuesta.body.isEmpty) {
      return null;
    }
    // utf8.decode sobre bodyBytes y no respuesta.body: http usa latin-1 cuando
    // la respuesta no declara charset, y ahi los nombres con tilde llegan rotos
    // — "Martínez" se convierte en "MartÃ­nez" y queda asi guardado.
    return jsonDecode(utf8.decode(respuesta.bodyBytes));
  }

  void _verificar(http.Response respuesta) {
    if (respuesta.statusCode >= 400) {
      throw ErrorDeApi(respuesta.statusCode, respuesta.body);
    }
  }

  void cerrar() => _http.close();
}

class ErrorDeApi implements Exception {
  const ErrorDeApi(this.codigo, this.cuerpo);

  final int codigo;
  final String cuerpo;

  @override
  String toString() => 'El servidor respondio $codigo';
}
</#noparse>
