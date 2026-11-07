import 'dart:ui' show Color;

import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/formato.dart';

void main() {
  test('colorDesdeHex', () {
    expect(colorDesdeHex('#CC79A7'), const Color(0xFFCC79A7));
    expect(colorDesdeHex('cc79a7'), const Color(0xFFCC79A7));
    expect(colorDesdeHex('#C7A'), const Color(0xFFCC77AA));
    expect(colorDesdeHex('#CC79A780'), const Color(0x80CC79A7));
    expect(colorDesdeHex(null), isNull);
    expect(colorDesdeHex(''), isNull);
    expect(colorDesdeHex('#ZZZZZZ'), isNull);
    expect(colorDesdeHex('rojo'), isNull);
  });

  test('horaLocal HH:mm', () {
    expect(horaLocal(DateTime(2026, 1, 1, 9, 5)), '09:05');
    expect(horaLocal(DateTime(2026, 1, 1, 23, 59)), '23:59');
    final utc = DateTime.utc(2026, 11, 5, 14, 3);
    final l = utc.toLocal();
    expect(horaLocal(utc), '${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')}');
  });
}
