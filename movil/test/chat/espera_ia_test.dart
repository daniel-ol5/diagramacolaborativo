import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/espera_ia.dart';
import 'package:movil/models/chat_message.dart';

ChatMessage m(int seq, {String type = 'USER', String? to, String kind = 'CHAT'}) => ChatMessage.fromJson({
      'id': 'm$seq',
      'seq': seq,
      'authorType': type,
      'addressedToId': ?to,
      'kind': kind,
    });

void main() {
  test('mencionaIa: palabra completa, sin distinguir mayusculas', () {
    for (final si in ['@IA hola', 'hola @ia', '¿@Ia, qué opinas?', '(@IA)', 'línea\n@IA']) {
      expect(mencionaIa(si), isTrue, reason: si);
    }
    for (final no in ['hola', 'hola@IA', '@IAs', '@ia_bot', 'IA sin arroba', '@@IA2']) {
      expect(mencionaIa(no), isFalse, reason: no);
    }
  });

  group('EsperaIa', () {
    final t0 = DateTime(2026, 11, 6, 10);

    test('termina con un mensaje de la IA dirigido a mi posterior a mi mensaje', () {
      final e = EsperaIa()..iniciar(5, t0);
      expect(e.activa(t0), isTrue);
      expect(e.procesar([m(4, type: 'AI', to: 'u1')], 'u1'), isFalse, reason: 'anterior a mi mensaje');
      expect(e.procesar([m(6, type: 'AI', to: 'u2')], 'u1'), isFalse, reason: 'para otra persona');
      expect(e.procesar([m(7, to: 'u1')], 'u1'), isFalse, reason: 'no es de la IA');
      expect(e.procesar([m(8, type: 'AI', to: 'u1', kind: 'ANSWER')], 'u1'), isTrue);
      expect(e.activa(t0), isFalse);
    });

    test('un aviso SYSTEM ("La IA no está disponible") NO la termina: espera los 30 s', () {
      final e = EsperaIa()..iniciar(5, t0);
      expect(e.procesar([m(6, type: 'SYSTEM', kind: 'SYSTEM')], 'u1'), isFalse);
      expect(e.activa(t0.add(const Duration(seconds: 10))), isTrue);
    });

    test('vence a los 30 s', () {
      final e = EsperaIa()..iniciar(5, t0);
      expect(e.activa(t0.add(const Duration(seconds: 29))), isTrue);
      expect(e.restante(t0.add(const Duration(seconds: 20))), const Duration(seconds: 10));
      expect(e.activa(t0.add(const Duration(seconds: 30))), isFalse);
      expect(e.restante(t0.add(const Duration(seconds: 40))), Duration.zero);
    });

    test('sin iniciar no esta activa ni termina nada', () {
      final e = EsperaIa();
      expect(e.activa(t0), isFalse);
      expect(e.procesar([m(1, type: 'AI', to: 'u1')], 'u1'), isFalse);
    });
  });
}
