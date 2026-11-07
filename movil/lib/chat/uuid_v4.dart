import 'dart:math';

final Random _azar = Random.secure();

/// UUID v4 (RFC 4122) para el clientMessageId del chat, sin agregar el
/// paquete uuid: son 16 bytes al azar con la version y la variante fijas.
/// Nunca empieza con "ai-" ni "sys-" (prefijos reservados del backend):
/// siempre son solo digitos hex y guiones.
String uuidV4() {
  final b = List<int>.generate(16, (_) => _azar.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40; // version 4
  b[8] = (b[8] & 0x3f) | 0x80; // variante RFC 4122
  final hex = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}
