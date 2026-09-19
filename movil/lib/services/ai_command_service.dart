import 'dart:convert';

import 'package:http/http.dart' as http;

/// Espejo de AiCommandResponse.java / web/src/modeling/aiCommandApi.ts
/// (CU16). El mismo cuerpo puede venir en dos codigos HTTP distintos, y la
/// diferencia importa para el tono con que se muestra:
///
/// - 200: aplicado, o un rechazo del guardarrail/catalogo -- es el
///   asistente respondiendo, no una falla ("Créame un sistema para un
///   banco" es 200 con applied:false).
/// - 502: el servicio de IA rompio el contrato o no respondio -- ahi si
///   hubo un problema real, aunque el diagrama no se haya tocado.
///
/// Cualquier otro status (401/403/500) es una falla de verdad.
class AiCommandResult {
  const AiCommandResult({
    required this.applied,
    required this.reason,
    required this.message,
    required this.httpStatus,
  });

  final bool applied;
  final String? reason;
  final String message;
  final int httpStatus;

  /// El servicio de IA rompio el contrato o no respondio -- distinto de un
  /// rechazo normal del guardarrail (eso SIGUE siendo applied:false con
  /// httpStatus 200).
  bool get esFalloDeVerdad => httpStatus != 200;

  factory AiCommandResult.fromJson(int httpStatus, Map<String, dynamic> json) => AiCommandResult(
        applied: json['applied'] as bool? ?? false,
        reason: json['reason'] as String?,
        message: json['message'] as String? ?? '',
        httpStatus: httpStatus,
      );
}

class AiCommandException implements Exception {
  AiCommandException(this.mensaje);
  final String mensaje;

  @override
  String toString() => mensaje;
}

/// POST /api/v1/diagrams/{id}/ai-command -- el MISMO endpoint que ya usa
/// AiCommandBox.tsx en la web. El telefono manda TEXTO (ya transcripto en
/// el dispositivo), nunca audio: el servidor no distingue si el texto vino
/// tipeado en la web o dictado en el telefono, y no tiene por que.
class AiCommandService {
  AiCommandService._();

  static Future<AiCommandResult> enviarComando({
    required String direccionServidor,
    required String token,
    required String diagramId,
    required String command,
    required String clientCommandId,
  }) async {
    final http.Response respuesta;
    try {
      respuesta = await http
          .post(
            Uri.parse('$direccionServidor/api/v1/diagrams/$diagramId/ai-command'),
            headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer $token'},
            body: jsonEncode({'command': command, 'clientCommandId': clientCommandId}),
          )
          .timeout(const Duration(seconds: 20));
    } catch (e) {
      throw AiCommandException('No se pudo mandar el comando. ($e)');
    }

    if (respuesta.statusCode != 200 && respuesta.statusCode != 502) {
      throw AiCommandException('El servidor respondió ${respuesta.statusCode}.');
    }

    final Map<String, dynamic> cuerpo;
    try {
      cuerpo = jsonDecode(respuesta.body) as Map<String, dynamic>;
    } catch (_) {
      throw AiCommandException('La respuesta del servidor no es válida.');
    }
    return AiCommandResult.fromJson(respuesta.statusCode, cuerpo);
  }
}
