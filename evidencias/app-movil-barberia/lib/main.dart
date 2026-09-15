// Generado por ModelCollab desde el diagrama "Barberia".
//
// Punto de entrada de la app con asistente de voz.
import 'package:flutter/material.dart';

import 'ui/pantalla_dictado.dart';

void main() {
  runApp(const AppDeVoz());
}

class AppDeVoz extends StatelessWidget {
  const AppDeVoz({super.key});

  static const String titulo = 'Barberia';

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: titulo,
      theme: ThemeData(useMaterial3: true),
      home: const PantallaDictado(titulo: titulo),
    );
  }
}
