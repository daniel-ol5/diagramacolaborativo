import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/separadores_dia.dart';

void main() {
  // Todas las fechas en hora local, asi el test no depende de la zona
  // horaria de la maquina que lo corre.
  final hoy = DateTime(2026, 11, 6, 15, 30);

  group('etiquetaDia', () {
    test('Hoy, a cualquier hora del dia', () {
      expect(etiquetaDia(DateTime(2026, 11, 6, 0, 0), hoy), 'Hoy');
      expect(etiquetaDia(DateTime(2026, 11, 6, 23, 59), hoy), 'Hoy');
    });

    test('Ayer', () {
      expect(etiquetaDia(DateTime(2026, 11, 5, 23, 59), hoy), 'Ayer');
      expect(etiquetaDia(DateTime(2026, 11, 5, 0, 1), hoy), 'Ayer');
    });

    test('mas viejo: "5 nov 2026" con mes abreviado en español', () {
      expect(etiquetaDia(DateTime(2026, 11, 4, 12), hoy), '4 nov 2026');
      expect(etiquetaDia(DateTime(2026, 1, 15), hoy), '15 ene 2026');
      expect(etiquetaDia(DateTime(2025, 8, 1), hoy), '1 ago 2025');
      const meses = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
      for (var m = 1; m <= 12; m++) {
        expect(etiquetaDia(DateTime(2020, m, 3), hoy), '3 ${meses[m - 1]} 2020');
      }
    });

    test('Ayer cruzando fin de mes y fin de año', () {
      expect(etiquetaDia(DateTime(2026, 10, 31, 22), DateTime(2026, 11, 1, 8)), 'Ayer');
      expect(etiquetaDia(DateTime(2026, 12, 31, 23), DateTime(2027, 1, 1, 0, 5)), 'Ayer');
      expect(etiquetaDia(DateTime(2028, 2, 29), DateTime(2028, 3, 1)), 'Ayer');
    });

    test('fecha futura (reloj atrasado) se muestra con la fecha, no "Hoy"', () {
      expect(etiquetaDia(DateTime(2026, 11, 7, 1), hoy), '7 nov 2026');
    });

    test('un createdAt en UTC se compara en hora local', () {
      final utc = DateTime.utc(2026, 11, 6, 15, 30);
      expect(etiquetaDia(utc, utc.toLocal()), 'Hoy');
    });
  });

  group('intercalarDias', () {
    List<String> describir(List<FilaChat<int>> filas) => [
          for (final f in filas)
            switch (f) {
              FilaDia<int>(:final etiqueta) => '[$etiqueta]',
              FilaElemento<int>(:final elemento) => '$elemento',
            },
        ];

    test('un separador antes del primer elemento de cada dia, respetando el orden', () {
      final fechas = {
        1: DateTime(2026, 11, 3, 9),
        2: DateTime(2026, 11, 3, 18),
        3: DateTime(2026, 11, 5, 10),
        4: DateTime(2026, 11, 6, 8),
        5: DateTime(2026, 11, 6, 9),
      };
      final filas = intercalarDias([1, 2, 3, 4, 5], (i) => fechas[i]!, hoy);
      expect(describir(filas), ['[3 nov 2026]', '1', '2', '[Ayer]', '3', '[Hoy]', '4', '5']);
    });

    test('lista vacia: sin separadores', () {
      expect(intercalarDias<int>([], (_) => hoy, hoy), isEmpty);
    });

    test('todo del mismo dia: un solo separador arriba', () {
      final filas = intercalarDias([1, 2, 3], (_) => hoy, hoy);
      expect(describir(filas), ['[Hoy]', '1', '2', '3']);
    });

    test('al anteponer mas viejos del mismo dia, el separador queda arriba de todo', () {
      final f = {1: DateTime(2026, 11, 6, 7), 2: DateTime(2026, 11, 6, 8), 3: DateTime(2026, 11, 6, 9)};
      expect(describir(intercalarDias([2, 3], (i) => f[i]!, hoy)), ['[Hoy]', '2', '3']);
      expect(describir(intercalarDias([1, 2, 3], (i) => f[i]!, hoy)), ['[Hoy]', '1', '2', '3']);
    });

    test('el separador trae el dia sin hora (clave estable)', () {
      final filas = intercalarDias([1], (_) => DateTime(2026, 11, 6, 15, 30), hoy);
      expect((filas.first as FilaDia<int>).dia, DateTime(2026, 11, 6));
    });
  });
}
