// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// Decide QUE intencion dispara una frase dictada. Todo local: no hay red.
import 'catalogo.dart';

/// Lo que el clasificador reconocio, o null si no reconocio nada.
class Reconocimiento {
  const Reconocimiento(this.intencion, this.frase, this.puntaje, this.resto);

  final Intencion intencion;

  /// La frase del catalogo que disparo.
  final String frase;

  /// Entre 0 y 1. Sirve para decidir si preguntar antes de actuar.
  final double puntaje;

  /// Lo que quedo del dictado despues de sacar la frase: de aca salen los
  /// datos. "crea una cita manana a las 10" deja "manana a las 10".
  final String resto;
}

class Clasificador {
  Clasificador(this.catalogo);

  final Catalogo catalogo;

  /// Por debajo de esto se prefiere no hacer nada.
  ///
  /// Un asistente que actua con poca confianza es peor que uno que pregunta:
  /// aca las acciones incluyen ELIMINAR, y equivocarse destruye datos del
  /// usuario. Ante la duda, que pregunte.
  static const double umbral = 0.6;

  Reconocimiento? clasificar(String dictado) {
    final String texto = normalizar(dictado);
    if (texto.isEmpty) {
      return null;
    }

    Reconocimiento? mejor;
    for (final Intencion intencion in catalogo.intenciones) {
      for (final String frase in intencion.frases) {
        final String patron = normalizar(frase);
        if (patron.isEmpty) {
          continue;
        }
        final double puntaje = _puntuar(texto, patron);
        if (puntaje < umbral) {
          continue;
        }
        // Ante empate gana la frase MAS LARGA. "crea una cita" y "cita"
        // pueden coincidir las dos con el mismo dictado, y la larga es la
        // que describe mejor lo que se pidio.
        if (mejor == null ||
            puntaje > mejor.puntaje ||
            (puntaje == mejor.puntaje && patron.length > mejor.frase.length)) {
          mejor = Reconocimiento(
            intencion,
            patron,
            puntaje,
            _quitar(texto, patron),
          );
        }
      }
    }
    return mejor;
  }

  /// Cuanto se parece el dictado a una frase del catalogo.
  ///
  /// No se compara por igualdad: el reconocimiento de voz devuelve lo que
  /// entendio, no lo que se dijo, y una frase exacta no coincide casi nunca.
  /// Se mide que proporcion de las palabras de la frase aparecen en el
  /// dictado, y empezar por ella suma — quien dicta una orden la dice al
  /// principio y despues los datos.
  double _puntuar(String texto, String patron) {
    if (texto.startsWith(patron)) {
      return 1.0;
    }
    final List<String> palabras = patron.split(' ');
    int encontradas = 0;
    for (final String palabra in palabras) {
      if (texto.contains(palabra)) {
        encontradas++;
      }
    }
    if (palabras.isEmpty) {
      return 0;
    }
    final double proporcion = encontradas / palabras.length;
    // Coincidir entero pero desordenado vale menos que coincidir al principio.
    return proporcion * 0.9;
  }

  String _quitar(String texto, String patron) {
    if (texto.startsWith(patron)) {
      return texto.substring(patron.length).trim();
    }
    String resto = texto;
    for (final String palabra in patron.split(' ')) {
      resto = resto.replaceFirst(palabra, '');
    }
    return resto.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  /// Minusculas, sin tildes y con los espacios colapsados.
  ///
  /// Sin quitar tildes, "crea una cita el miercoles" y "crea una cita el
  /// miercoles" con tilde serian frases distintas, y el reconocedor devuelve
  /// una u otra segun el modelo de idioma que tenga instalado.
  static String normalizar(String texto) {
    const String con = 'áàäâéèëêíìïîóòöôúùüûñç';
    const String sin = 'aaaaeeeeiiiioooouuuunc';
    final StringBuffer salida = StringBuffer();
    for (final int unidad in texto.toLowerCase().runes) {
      final String caracter = String.fromCharCode(unidad);
      final int indice = con.indexOf(caracter);
      salida.write(indice >= 0 ? sin[indice] : caracter);
    }
    return salida
        .toString()
        .replaceAll(RegExp(r'[^a-z0-9 ]'), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }
}
