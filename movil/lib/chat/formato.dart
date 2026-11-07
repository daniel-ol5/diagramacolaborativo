import 'dart:ui' show Color;

/// "#CC79A7" (o "CC79A7", "#C7A", "#CC79A7FF" estilo CSS) -> Color.
/// Devuelve null si falta o no se entiende; la pantalla usa gris.
Color? colorDesdeHex(String? hex) {
  if (hex == null) return null;
  var h = hex.trim();
  if (h.startsWith('#')) h = h.substring(1);
  if (h.length == 3) h = h.split('').map((c) => '$c$c').join();
  if (h.length == 6) h = '${h}ff';
  if (h.length != 8) return null;
  final v = int.tryParse(h, radix: 16);
  if (v == null) return null;
  // CSS es RRGGBBAA; Flutter quiere AARRGGBB.
  return Color(((v & 0xff) << 24) | (v >> 8));
}

/// Hora local HH:mm de una fecha (el servidor manda UTC).
String horaLocal(DateTime t) {
  final l = t.toLocal();
  return '${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')}';
}
