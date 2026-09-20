import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

/// Espejo de la respuesta de POST /api/v1/diagrams/{id}/vision-capture,
/// contrato dado por el backend (ver evidencias/verificar-captura.mjs):
/// siempre 200, nunca un error -- si nadie tiene el diagrama abierto en la
/// computadora, la foto ni se analiza (delivered:false) y el mensaje ya
/// dice que hacer.
class VisionCaptureResult {
  const VisionCaptureResult({
    required this.delivered,
    required this.reason,
    required this.message,
  });

  final bool delivered;
  final String? reason;
  final String message;

  factory VisionCaptureResult.fromJson(Map<String, dynamic> json) => VisionCaptureResult(
        delivered: json['delivered'] as bool? ?? false,
        reason: json['reason'] as String?,
        message: json['message'] as String? ?? '',
      );
}

class VisionCaptureException implements Exception {
  VisionCaptureException(this.mensaje);
  final String mensaje;

  @override
  String toString() => mensaje;
}

/// El telefono NUNCA revisa ni corrige la propuesta -- eso es CU17 en la
/// web (analyze/confirm). Aca solo se sube la foto y se muestra lo que
/// diga el servidor: "enviado, revisala en la computadora" o por que no
/// se pudo. El servidor empuja el resultado del analisis a
/// /user/queue/vision de esa MISMA persona -- este cliente ni se
/// suscribe a eso, no es asunto suyo.
///
/// DISEÑO PENDIENTE, anotado a proposito (pase con telefono real del
/// 20/09): esta llamada debería terminar apenas el servidor CONFIRMA que
/// recibio la imagen, no esperar a que el modelo la analice -- el
/// resultado ya viaja aparte por /user/queue/vision. Hoy el telefono
/// manda Y espera el analisis completo, que es media decision: el diseño
/// acordado es "el telefono manda, la web revisa", y esperar lo ata a un
/// tiempo que no controla. No se hizo ahora por el tiempo que queda antes
/// de la defensa.
class VisionCaptureService {
  VisionCaptureService._();

  static Future<VisionCaptureResult> subirFoto({
    required String direccionServidor,
    required String token,
    required String diagramId,
    required File foto,
  }) async {
    final http.Response respuesta;
    try {
      final request = http.MultipartRequest(
        'POST',
        Uri.parse('$direccionServidor/api/v1/diagrams/$diagramId/vision-capture'),
      )
        ..headers['Authorization'] = 'Bearer $token'
        ..files.add(await http.MultipartFile.fromPath('image', foto.path));
      // Mismo motivo que ai_command_service.dart: el servidor puede tardar
      // hasta el tope de reintentos ante Gemini (45s) antes de contestar;
      // 30s hacia que el telefono se rindiera antes de que el servidor
      // terminara de intentar.
      final streamed = await request.send().timeout(const Duration(seconds: 60));
      respuesta = await http.Response.fromStream(streamed);
    } catch (e) {
      throw VisionCaptureException('No se pudo subir la foto. ($e)');
    }

    if (respuesta.statusCode != 200) {
      String mensaje = 'HTTP ${respuesta.statusCode}';
      try {
        final cuerpo = jsonDecode(respuesta.body) as Map<String, dynamic>;
        if (cuerpo['message'] is String) mensaje = cuerpo['message'] as String;
      } catch (_) {
        // el cuerpo no era JSON -- se deja el HTTP status como mensaje
      }
      throw VisionCaptureException(mensaje);
    }

    return VisionCaptureResult.fromJson(jsonDecode(respuesta.body) as Map<String, dynamic>);
  }
}
