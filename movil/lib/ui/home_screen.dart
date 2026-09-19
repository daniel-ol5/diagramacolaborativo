import 'package:flutter/material.dart';

import '../services/config_service.dart';
import 'login_screen.dart';

/// Placeholder hasta el punto 2 de la cola (elegir proyecto y diagrama).
/// Confirma que la sesion quedo guardada y que cerrar sesion funciona --
/// nada mas todavia.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.sesion});

  final Sesion sesion;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(sesion.nombreCompleto),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Cerrar sesión',
            onPressed: () async {
              await ConfigService.cerrarSesion();
              if (!context.mounted) return;
              Navigator.of(context).pushReplacement(
                MaterialPageRoute(builder: (_) => const LoginScreen()),
              );
            },
          ),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Sesión activa: ${sesion.email}'),
              const SizedBox(height: 8),
              Text('Servidor: ${sesion.direccionServidor}'),
              const SizedBox(height: 24),
              const Text('El selector de proyecto y diagrama va acá.'),
            ],
          ),
        ),
      ),
    );
  }
}
