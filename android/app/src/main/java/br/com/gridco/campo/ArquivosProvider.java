package br.com.gridco.campo;

import androidx.core.content.FileProvider;

/**
 * Segundo FileProvider do app, com a authority br.com.gridco.campo.arquivos, para o APK da atualização da casca.
 * O primeiro (".fileprovider") é o do Capacitor e serve à câmera. A subclasse dá nome próprio ao segundo componente,
 * para não colidir com o FileProvider do template nem com o de algum plugin futuro.
 */
public class ArquivosProvider extends FileProvider {}
