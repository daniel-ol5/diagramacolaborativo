// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// Punto de entrada de la app. Todavia es el esqueleto: lo que hace falta que
// exista para que el arnes pueda analizar el proyecto de punta a punta.
import 'package:flutter/material.dart';

void main() {
  runApp(const AppDeVoz());
}

class AppDeVoz extends StatelessWidget {
  const AppDeVoz({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '${nombreDelDiagrama?json_string}',
      theme: ThemeData(useMaterial3: true),
      home: const PantallaPrincipal(),
    );
  }
}

class PantallaPrincipal extends StatelessWidget {
  const PantallaPrincipal({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('${nombreDelDiagrama?json_string}')),
      body: const Center(
        child: Text('Manten presionado el microfono y dicta una orden.'),
      ),
    );
  }
}
