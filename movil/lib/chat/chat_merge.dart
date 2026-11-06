import '../models/chat_message.dart';

/// Fusiona dos listas de mensajes del servidor: ordena por seq (los huecos
/// se ignoran, no se rellenan) y deduplica por id y por clientMessageId.
///
/// - Mismo id: queda el que viene en [nuevos] (la version mas reciente).
/// - Mismo clientMessageId con ids distintos (no deberia pasar, pero un
///   reintento podria provocarlo): queda el de menor seq.
/// - Un mensaje sin id no se puede deduplicar y se descarta.
List<ChatMessage> fusionarMensajes(Iterable<ChatMessage> actuales, Iterable<ChatMessage> nuevos) {
  final porId = <String, ChatMessage>{};
  for (final m in actuales) {
    if (m.id.isNotEmpty) porId[m.id] = m;
  }
  for (final m in nuevos) {
    if (m.id.isNotEmpty) porId[m.id] = m;
  }
  final ordenados = porId.values.toList()
    ..sort((a, b) {
      final c = a.seq.compareTo(b.seq);
      return c != 0 ? c : a.id.compareTo(b.id);
    });
  final cmidsVistos = <String>{};
  return [
    for (final m in ordenados)
      if (m.clientMessageId == null || cmidsVistos.add(m.clientMessageId!)) m,
  ];
}

/// Un mensaje propio que todavia no confirmo el servidor (envio optimista).
class MensajePendiente {
  const MensajePendiente({
    required this.clientMessageId,
    required this.text,
    required this.creado,
    this.fallido = false,
    this.error,
  });

  final String clientMessageId;
  final String text;
  final DateTime creado;
  final bool fallido;

  /// Por que no se envio, ya en español (solo si [fallido]).
  final String? error;

  MensajePendiente _con({required bool fallido, String? error}) => MensajePendiente(
        clientMessageId: clientMessageId,
        text: text,
        creado: creado,
        fallido: fallido,
        error: error,
      );
}

/// Estado del chat sin widgets: mensajes confirmados (ordenados por seq)
/// mas los pendientes propios, que se muestran al final hasta que el
/// servidor los confirma -- sea por la respuesta del POST o porque el
/// sondeo los trajo primero. Las dos vias pasan por [fusionar], asi que
/// el orden en que lleguen no importa y nunca hay duplicados.
class ChatTimeline {
  List<ChatMessage> _mensajes = const [];
  final List<MensajePendiente> _pendientes = [];

  List<ChatMessage> get mensajes => _mensajes;
  List<MensajePendiente> get pendientes => List.unmodifiable(_pendientes);
  int get total => _mensajes.length + _pendientes.length;
  bool get vacio => total == 0;

  /// El seq mas alto confirmado: el cursor para pedir lo nuevo.
  int? get ultimoSeq => _mensajes.isEmpty ? null : _mensajes.last.seq;

  /// El seq mas bajo cargado: el cursor para pedir lo anterior (CU23).
  int? get primerSeq => _mensajes.isEmpty ? null : _mensajes.first.seq;

  /// Agrega mensajes del servidor y devuelve los que no se conocian.
  /// Un pendiente cuyo clientMessageId aparece queda confirmado.
  /// Sirve igual para lo nuevo (sondeo, POST) que para anteponer lo
  /// anterior (historial): todo se reordena por seq y se deduplica.
  List<ChatMessage> fusionar(Iterable<ChatMessage> nuevos) {
    final antes = {for (final m in _mensajes) m.id};
    _mensajes = List.unmodifiable(fusionarMensajes(_mensajes, nuevos));
    final cmids = {for (final m in _mensajes) ?m.clientMessageId};
    _pendientes.removeWhere((p) => cmids.contains(p.clientMessageId));
    return [for (final m in _mensajes) if (!antes.contains(m.id)) m];
  }

  /// No hace nada si ese clientMessageId ya esta pendiente o confirmado.
  void agregarPendiente(String clientMessageId, String text, DateTime creado) {
    if (pendiente(clientMessageId) != null) return;
    if (_mensajes.any((m) => m.clientMessageId == clientMessageId)) return;
    _pendientes.add(MensajePendiente(clientMessageId: clientMessageId, text: text, creado: creado));
  }

  MensajePendiente? pendiente(String clientMessageId) {
    for (final p in _pendientes) {
      if (p.clientMessageId == clientMessageId) return p;
    }
    return null;
  }

  /// Vuelve a "enviando" (al reintentar, con el MISMO clientMessageId).
  void marcarEnviando(String clientMessageId) => _reemplazar(clientMessageId, (p) => p._con(fallido: false));

  void marcarFallido(String clientMessageId, String error) =>
      _reemplazar(clientMessageId, (p) => p._con(fallido: true, error: error));

  void _reemplazar(String clientMessageId, MensajePendiente Function(MensajePendiente) cambio) {
    final i = _pendientes.indexWhere((p) => p.clientMessageId == clientMessageId);
    if (i >= 0) _pendientes[i] = cambio(_pendientes[i]);
  }
}
