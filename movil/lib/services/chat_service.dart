import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/chat_message.dart';

/// Error del chat ya traducido al español para mostrarlo tal cual.
///
/// [status] 0 = no hubo conexion (sin red, servidor caido o timeout): la
/// pantalla lo usa para deshabilitar el campo mientras dure. -1 = el
/// servidor contesto algo que no se pudo leer. Cualquier otro valor es el
/// codigo HTTP. [code] es el `code` del cuerpo cuando viene (INVALID_PAYLOAD,
/// RATE_LIMITED); un 403 puede llegar sin code.
class ChatException implements Exception {
  ChatException(this.status, this.mensaje, {this.code});

  final int status;
  final String? code;
  final String mensaje;

  bool get sinConexion => status == 0;
  bool get noEsMiembro => status == 403;
  bool get demasiadosMensajes => status == 429;

  @override
  String toString() => mensaje;
}

/// Chat del proyecto por REST + sondeo (CU19). Sin STOMP a proposito: la
/// respuesta de la IA NO viene en el POST, se recoge despues con
/// [fetchAfter]. Mismo estilo que ApiService (instancia con servidor y
/// token de la Sesion); [client] existe para poder probarlo con MockClient.
///
/// El autor sale del token: NUNCA se manda en el cuerpo (el backend
/// rechaza campos desconocidos).
class ChatService {
  ChatService({required this.direccionServidor, required this.token, http.Client? client})
      : _client = client ?? http.Client();

  final String direccionServidor;
  final String token;
  final http.Client _client;

  static const int maxTexto = 2000;
  static const int limitePorDefecto = 50;
  static const int limiteMaximo = 100;

  /// El GET es liviano y se repite cada pocos segundos: si tarda mas que
  /// esto, se considera sin conexion y el proximo ciclo vuelve a probar.
  static const Duration _timeoutLectura = Duration(seconds: 10);

  /// El POST solo guarda el mensaje (la IA contesta aparte), asi que no
  /// necesita los 60 s de ai-command.
  static const Duration _timeoutEnvio = Duration(seconds: 15);

  /// Los ultimos [limit] mensajes, ascendentes por seq.
  Future<ChatPage> fetchLatest(String projectId, {int limit = limitePorDefecto}) =>
      _leer(projectId, {'limit': '${_limitar(limit)}'});

  /// Los PRIMEROS mensajes con seq > [afterSeq]. hasMore=true significa que
  /// hay mas nuevos todavia: hay que volver a pedir de inmediato.
  Future<ChatPage> fetchAfter(String projectId, int afterSeq, {int limit = limitePorDefecto}) =>
      _leer(projectId, {'afterSeq': '$afterSeq', 'limit': '${_limitar(limit)}'});

  /// Mensajes con seq < [beforeSeq] (para "cargar anteriores", otro CU).
  Future<ChatPage> fetchBefore(String projectId, int beforeSeq, {int limit = limitePorDefecto}) =>
      _leer(projectId, {'beforeSeq': '$beforeSeq', 'limit': '${_limitar(limit)}'});

  /// 201 = guardado; 200 = era un duplicado y vuelve el original. Las dos
  /// cosas son exito para quien reintenta con el MISMO [clientMessageId].
  Future<ChatMessage> send(
    String projectId,
    String text,
    String clientMessageId, {
    String? diagramId,
    String? replyToId,
  }) async {
    if (text.trim().isEmpty) {
      throw ChatException(400, 'El mensaje está vacío.', code: 'INVALID_PAYLOAD');
    }
    if (text.length > maxTexto) {
      throw ChatException(400, 'El mensaje no puede superar $maxTexto caracteres.', code: 'INVALID_PAYLOAD');
    }
    final cuerpo = <String, dynamic>{
      'clientMessageId': clientMessageId,
      'text': text,
      'diagramId': ?diagramId,
      'replyToId': ?replyToId,
    };

    final http.Response respuesta;
    try {
      respuesta = await _client
          .post(
            _uri(projectId),
            headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer $token'},
            body: jsonEncode(cuerpo),
          )
          .timeout(_timeoutEnvio);
    } catch (_) {
      throw ChatException(0, 'Sin conexión con el servidor. El mensaje no se envió.');
    }

    if (respuesta.statusCode != 200 && respuesta.statusCode != 201) {
      throw _traducir(respuesta);
    }
    final json = _decodificar(respuesta);
    if (json is! Map) throw ChatException(-1, 'La respuesta del servidor no es válida.');
    return ChatMessage.fromJson(Map<String, dynamic>.from(json));
  }

  Future<ChatPage> _leer(String projectId, Map<String, String> query) async {
    final http.Response respuesta;
    try {
      respuesta = await _client
          .get(_uri(projectId, query), headers: {'Authorization': 'Bearer $token'})
          .timeout(_timeoutLectura);
    } catch (_) {
      throw ChatException(0, 'Sin conexión con el servidor.');
    }

    if (respuesta.statusCode != 200) throw _traducir(respuesta);
    final json = _decodificar(respuesta);
    if (json is! Map) throw ChatException(-1, 'La respuesta del servidor no es válida.');
    return ChatPage.fromJson(Map<String, dynamic>.from(json));
  }

  Uri _uri(String projectId, [Map<String, String>? query]) {
    final base = Uri.parse('$direccionServidor/api/v1/projects/${Uri.encodeComponent(projectId)}/chat');
    return query == null ? base : base.replace(queryParameters: query);
  }

  static int _limitar(int limit) => limit.clamp(1, limiteMaximo);

  static Object? _decodificar(http.Response r) {
    try {
      return jsonDecode(utf8.decode(r.bodyBytes));
    } catch (_) {
      throw ChatException(-1, 'La respuesta del servidor no es válida.');
    }
  }

  /// Cualquier 403 es "no eres miembro", venga o no con code (mismo criterio
  /// que ApiException: no se distingue de "el proyecto no existe").
  static ChatException _traducir(http.Response r) {
    String? code;
    String? mensajeServidor;
    try {
      final json = jsonDecode(utf8.decode(r.bodyBytes));
      if (json is Map) {
        if (json['code'] is String) code = json['code'] as String;
        if (json['message'] is String && (json['message'] as String).isNotEmpty) {
          mensajeServidor = json['message'] as String;
        }
      }
    } catch (_) {
      // cuerpo vacio o no JSON: se usa el mensaje generico del status
    }

    final status = r.statusCode;
    final String mensaje;
    switch (status) {
      case 400:
        mensaje = mensajeServidor ?? 'El servidor rechazó el mensaje.';
      case 401:
        mensaje = 'Tu sesión expiró. Vuelve a iniciar sesión.';
      case 403:
        mensaje = 'No eres miembro de este proyecto.';
      case 429:
        mensaje = 'Estás enviando demasiados mensajes; espera un momento.';
      default:
        mensaje = 'El servidor respondió $status.';
    }
    return ChatException(status, mensaje, code: code);
  }
}
