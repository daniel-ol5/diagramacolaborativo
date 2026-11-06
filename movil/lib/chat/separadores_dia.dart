/// Separadores de dia del chat (CU23): "Hoy", "Ayer" o "5 nov 2026", en
/// hora local. Sin intl (no esta en pubspec): meses a mano.
library;

const List<String> _meses = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];

/// El dia calendario local de [t] (sin hora).
DateTime diaLocal(DateTime t) {
  final l = t.toLocal();
  return DateTime(l.year, l.month, l.day);
}

/// "Hoy", "Ayer" o "5 nov 2026". [hoy] es inyectable para los tests; en
/// la app es DateTime.now(). Una fecha futura (reloj del telefono
/// atrasado) se muestra con la fecha, nunca como "Hoy".
String etiquetaDia(DateTime fecha, DateTime hoy) {
  final d = diaLocal(fecha);
  final h = diaLocal(hoy);
  if (d == h) return 'Hoy';
  // DateTime(.., dia - 1) resuelve fin de mes y de año (1 ene -> 31 dic).
  if (d == DateTime(h.year, h.month, h.day - 1)) return 'Ayer';
  return '${d.day} ${_meses[d.month - 1]} ${d.year}';
}

/// Una fila de la lista del chat: un separador de dia o un elemento.
sealed class FilaChat<T> {
  const FilaChat();
}

class FilaDia<T> extends FilaChat<T> {
  const FilaDia(this.dia, this.etiqueta);

  /// Dia local (sin hora), util como clave del widget.
  final DateTime dia;
  final String etiqueta;
}

class FilaElemento<T> extends FilaChat<T> {
  const FilaElemento(this.elemento);
  final T elemento;
}

/// Intercala un separador antes del primer elemento de cada dia. [items]
/// tiene que venir en orden (por seq, y los pendientes al final); el
/// orden se respeta tal cual.
List<FilaChat<T>> intercalarDias<T>(List<T> items, DateTime Function(T) fecha, DateTime hoy) {
  final filas = <FilaChat<T>>[];
  DateTime? diaAnterior;
  for (final item in items) {
    final dia = diaLocal(fecha(item));
    if (dia != diaAnterior) {
      filas.add(FilaDia<T>(dia, etiquetaDia(dia, hoy)));
      diaAnterior = dia;
    }
    filas.add(FilaElemento<T>(item));
  }
  return filas;
}
