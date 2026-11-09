package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Config é a configuração local do daemon (persistida em JSON no diretório
// de dados do app).
type Config struct {
	// AllowedNumbers é a lista branca de remetentes (vazio = todos).
	AllowedNumbers []string `json:"allowedNumbers"`
	// AcceptAll desliga o filtro (equivale a lista vazia).
	AcceptAll bool `json:"acceptAll"`
}

// ConfigStore guarda a configuração em disco.
type ConfigStore struct {
	mu   sync.RWMutex
	path string
	cfg  Config
}

// NewConfigStore carrega (ou cria) a configuração no diretório.
func NewConfigStore(dir string) (*ConfigStore, error) {
	s := &ConfigStore{path: filepath.Join(dir, "config.json")}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(s.path)
	if err == nil {
		_ = json.Unmarshal(data, &s.cfg)
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return s, nil
}

// Allowed devolve a lista de números permitidos (nil = todos).
func (s *ConfigStore) Allowed() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.cfg.AcceptAll {
		return nil
	}
	out := make([]string, 0, len(s.cfg.AllowedNumbers))
	for _, n := range s.cfg.AllowedNumbers {
		out = append(out, strings.TrimSpace(n))
	}
	return out
}

// SetAllowed grava a lista branca.
func (s *ConfigStore) SetAllowed(numbers []string, acceptAll bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clean := make([]string, 0, len(numbers))
	for _, n := range numbers {
		n = strings.TrimSpace(n)
		if n != "" {
			clean = append(clean, n)
		}
	}
	s.cfg = Config{AllowedNumbers: clean, AcceptAll: acceptAll}
	data, err := json.MarshalIndent(s.cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0o600)
}

// IsAllowed diz se o número pode entrar.
//
// Mesma regra do servidor Node: lista vazia = todos; lista preenchida = só
// os números exatos ou os que terminam com o sufixo informado.
func IsAllowed(allowed []string, number string) bool {
	if len(allowed) == 0 {
		return true
	}
	n := onlyDigits(number)
	if n == "" {
		return false
	}
	for _, a := range allowed {
		a = onlyDigits(a)
		if a == "" {
			continue
		}
		if a == n || strings.HasSuffix(n, a) {
			return true
		}
	}
	return false
}

// onlyDigits tira tudo que não é dígito.
func onlyDigits(v string) string {
	var b strings.Builder
	for _, r := range v {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}
