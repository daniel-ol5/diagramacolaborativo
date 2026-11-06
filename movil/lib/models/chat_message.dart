/// Espejo del mensaje de GET/POST /api/v1/projects/{id}/chat (CU19).
///
/// El backend OMITE los campos nulos, asi que todo lo opcional puede no
/// venir. fromJson nunca tira por un campo ausente, nulo o de otro tipo:
/// un mensaje raro se muestra con valores por defecto en vez de tumbar el
/// chat entero. Dart puro (sin Flutter) para poder probarlo sin widgets.
class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.projectId,
    required this.seq,
    required this.authorType,
    required this.authorName,
    required this.kind,
    required this.text,
    required this.createdAt,
    this.diagramId,
    this.authorId,
    this.authorColor,
    this.addressedToId,
    this.addressedToName,
    this.replyToId,
    this.clientMessageId,
    this.meta,
  });

  final String id;
  final String projectId;
  final String? diagramId;
  final int seq;

  /// USER | AI | SYSTEM. Se guarda como texto: un valor nuevo del backend
  /// no rompe el parseo (se muestra como un mensaje comun).
  final String authorType;
  final String? authorId;
  final String authorName;

  /// Hex tipo "#CC79A7". Los mensajes de la IA no lo traen.
  final String? authorColor;
  final String? addressedToId;
  final String? addressedToName;
  final String? replyToId;
  final String? clientMessageId;

  /// CHAT | ANSWER | REVIEW | SUMMARY | SYSTEM. Siempre se muestra como
  /// texto plano, sea cual sea.
  final String kind;
  final String text;

  /// En UTC, como lo manda el servidor. La pantalla lo pasa a hora local.
  final DateTime createdAt;
  final Map<String, dynamic>? meta;

  bool get esDeIa => authorType == 'AI';
  bool get esDeSistema => authorType == 'SYSTEM' || kind == 'SYSTEM';

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: _texto(json['id']) ?? '',
        projectId: _texto(json['projectId']) ?? '',
        diagramId: _opcional(json['diagramId']),
        seq: _entero(json['seq']),
        authorType: (_texto(json['authorType']) ?? 'USER').toUpperCase(),
        authorId: _opcional(json['authorId']),
        authorName: _texto(json['authorName']) ?? '',
        authorColor: _opcional(json['authorColor']),
        addressedToId: _opcional(json['addressedToId']),
        addressedToName: _opcional(json['addressedToName']),
        replyToId: _opcional(json['replyToId']),
        clientMessageId: _opcional(json['clientMessageId']),
        kind: (_texto(json['kind']) ?? 'CHAT').toUpperCase(),
        text: _texto(json['text']) ?? '',
        createdAt: _fecha(json['createdAt']),
        meta: json['meta'] is Map ? Map<String, dynamic>.from(json['meta'] as Map) : null,
      );

  static String? _texto(Object? v) => v is String ? v : null;

  /// Un opcional vacio ("") se trata igual que ausente.
  static String? _opcional(Object? v) => v is String && v.isNotEmpty ? v : null;

  static int _entero(Object? v) {
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? 0;
    return 0;
  }

  static DateTime _fecha(Object? v) {
    final f = v is String ? DateTime.tryParse(v) : null;
    return (f ?? DateTime.fromMillisecondsSinceEpoch(0, isUtc: true)).toUtc();
  }
}

/// Respuesta de GET /chat: {messages, hasMore}, siempre ascendente por seq.
class ChatPage {
  const ChatPage({required this.messages, required this.hasMore});

  final List<ChatMessage> messages;
  final bool hasMore;

  factory ChatPage.fromJson(Map<String, dynamic> json) {
    final lista = json['messages'];
    return ChatPage(
      messages: lista is List
          ? lista.whereType<Map>().map((m) => ChatMessage.fromJson(Map<String, dynamic>.from(m))).toList()
          : const [],
      hasMore: json['hasMore'] == true,
    );
  }
}
