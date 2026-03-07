# =============================================================================
# Vibranium Platform — Docker Secrets Templates
# =============================================================================
# Este diretório contém os arquivos de secret para Docker Compose.
# Cada arquivo contém UMA credencial (sem newlines extras).
#
# INSTRUÇÕES:
#   1. Copie os arquivos .txt.example → .txt
#   2. Substitua <CHANGE_ME> pelo valor real da credencial
#   3. NUNCA faça commit dos arquivos .txt (estão no .gitignore)
#
# PERMISSÕES (Linux/macOS):
#   chmod 0400 infra/secrets/*.txt
#
# Gerar senhas seguras:
#   openssl rand -hex 32
#   openssl rand -base64 32
# =============================================================================
