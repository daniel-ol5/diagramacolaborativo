# El conjunto de reglas se fija ACA y no se hereda sin mas de flutter_lints.
#
# Motivo: el arnes del generador corre `flutter analyze` sobre esta app y falla
# si hay errores o advertencias. Si las reglas vinieran solo del paquete, una
# actualizacion suya podria introducir una regla nueva y poner en rojo un build
# que no cambio ni una linea. Se hereda la base, y lo que decide el arnes queda
# escrito aca.
include: package:flutter_lints/flutter.yaml

analyzer:
  errors:
    # Estas son defectos reales en codigo generado —nosotros escribimos cada
    # linea, asi que un import sin usar es un error de plantilla— y por eso se
    # suben a error en vez de quedar como aviso.
    unused_import: error
    unused_local_variable: error
    dead_code: error
