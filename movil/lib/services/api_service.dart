import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/diagram_summary.dart';
import '../models/project.dart';

/// Mismo criterio que ApiError en web/src/access/api.ts: guarda el `status`
/// HTTP y el `message` que manda el servidor (Spring por defecto,
/// ResponseStatusException), sin intentar distinguir un 403 de "no sos
/// miembro" de uno de "proyecto inexistente" -- es el mismo caso a
/// proposito, para no poder averiguar que proyectos existen probando ids.
class ApiException implements Exception {
  ApiException(this.status, this.message);
  final int status;
  final String message;

  @override
  String toString() => message;
}

/// Lecturas de solo-lista contra el mismo backend y los mismos endpoints
/// que ya usa la web (web/src/access/api.ts) -- el telefono no tiene su
/// propia API, es la misma cuenta viendo los mismos proyectos.
class ApiService {
  ApiService({required this.direccionServidor, required this.token});

  final String direccionServidor;
  final String token;

  Future<T> _get<T>(String ruta, T Function(dynamic) parse) async {
    final http.Response respuesta;
    try {
      respuesta = await http.get(
        Uri.parse('$direccionServidor$ruta'),
        headers: {'Authorization': 'Bearer $token'},
      ).timeout(const Duration(seconds: 10));
    } catch (e) {
      throw ApiException(0, 'No se pudo conectar con el servidor. ($e)');
    }

    if (respuesta.statusCode != 200) {
      String mensaje = 'HTTP ${respuesta.statusCode}';
      try {
        final cuerpo = jsonDecode(respuesta.body) as Map<String, dynamic>;
        if (cuerpo['message'] is String) mensaje = cuerpo['message'] as String;
      } catch (_) {
        // el cuerpo no era JSON (o estaba vacio) -- se deja el HTTP status como mensaje
      }
      throw ApiException(respuesta.statusCode, mensaje);
    }
    return parse(jsonDecode(respuesta.body));
  }

  Future<List<Project>> listarProyectos() {
    return _get(
      '/api/v1/projects',
      (json) => (json as List<dynamic>).map((p) => Project.fromJson(p as Map<String, dynamic>)).toList(),
    );
  }

  Future<List<DiagramSummary>> listarDiagramas(String projectId) {
    return _get(
      '/api/v1/projects/$projectId/diagrams',
      (json) => (json as List<dynamic>).map((d) => DiagramSummary.fromJson(d as Map<String, dynamic>)).toList(),
    );
  }
}
