/// Espejo de ChatReadState.java (GET/PUT /chat/read, CU23).
///
/// - lastReadSeq: el ultimo seq que esta persona marco como leido (0 si nunca).
/// - unreadCount: mensajes con seq > lastReadSeq que NO son suyos (los de la
///   IA y del sistema si cuentan).
/// - lastSeq: el mayor seq del proyecto (0 si no hay mensajes).
///
/// Tolerante: un campo ausente, nulo o de otro tipo vale 0.
class ChatReadState {
  const ChatReadState({required this.lastReadSeq, required this.unreadCount, required this.lastSeq});

  final int lastReadSeq;
  final int unreadCount;
  final int lastSeq;

  factory ChatReadState.fromJson(Map<String, dynamic> json) => ChatReadState(
        lastReadSeq: _entero(json['lastReadSeq']),
        unreadCount: _entero(json['unreadCount']),
        lastSeq: _entero(json['lastSeq']),
      );

  static int _entero(Object? v) {
    if (v is num) return v.toInt() < 0 ? 0 : v.toInt();
    if (v is String) {
      final n = int.tryParse(v) ?? 0;
      return n < 0 ? 0 : n;
    }
    return 0;
  }
}
