# Recordatorios (Android 14+)

App nativa y liviana de recordatorios con notificaciones de Android. Funciona sin internet y sin servidor.
Se compila sola en GitHub Actions: no hace falta Android Studio.

## Funciones (versión 1)
- Crear, editar y borrar recordatorios (título, nota, fecha y hora).
- Repetición: no se repite, diaria, semanal (días elegidos) o mensual.
- Notificación con botones **Hecho** y **Posponer 10 min**.
- Lista de próximos y vencidos (tocar = editar, mantener presionado = borrar).
- Reprograma todo al reiniciar el celular, cambiar la hora o actualizar la app.

## Puesta en marcha (una sola vez)

1. **Crear el repositorio** en GitHub: *New repository* → nombre `recordatorios` → **Private** → *Create*.
2. **Cargar los secretos de firma** (antes de subir el código):
   *Settings → Secrets and variables → Actions → New repository secret*. Crear dos, con los valores del archivo `secretos-github.txt`:
   - `KEYSTORE_B64`
   - `KEYSTORE_PASS`
3. **Subir los archivos** (desde la PC es más cómodo): *Add file → Upload files* y arrastrar el **contenido** de la carpeta `recordatorios` (no la carpeta en sí). Confirmar con *Commit changes*.
4. **Revisar que exista el workflow** `.github/workflows/compilar.yml`. Algunos sistemas ocultan las carpetas que empiezan con punto y no se suben. Si falta: *Add file → Create new file*, escribir como nombre `.github/workflows/compilar.yml` y pegar el contenido del archivo.
5. **Esperar la compilación** en la pestaña *Actions* (unos minutos). Si sale en verde, el APK queda en *Releases*.

## Instalar en el celular
1. Desde el celular, abrir el repositorio → *Releases* → descargar `recordatorios-1.0.N.apk`.
2. Abrirlo y permitir "instalar apps desconocidas" para el navegador cuando lo pida.
3. Play Protect puede advertir que es una app desconocida: es esperable porque no viene de Play Store.
4. Abrir la app y aceptar el permiso de notificaciones.

## Actualizar
Cada cambio que se suba a la rama `main` genera una versión nueva en *Releases*. Se instala encima de la anterior y los recordatorios se conservan.
También se puede recompilar a mano: *Actions → Compilar APK → Run workflow*.

## Importante
- Guardá `secretos-github.txt` en un lugar seguro y **no lo subas al repositorio**. Si se pierde la clave, las versiones nuevas no se podrán instalar encima: habría que desinstalar la app y se perderían los recordatorios.
- Si ves un aviso amarillo dentro de la app, tocalo: lleva al ajuste de permisos que falta.
