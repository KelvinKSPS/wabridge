package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIsAllowedSemListaAceitaTodos(t *testing.T) {
	if !IsAllowed(nil, "5511999999999") {
		t.Fatal("lista vazia deveria aceitar todo mundo")
	}
	if !IsAllowed([]string{}, "5511999999999") {
		t.Fatal("lista vazia deveria aceitar todo mundo")
	}
}

func TestIsAllowedComLista(t *testing.T) {
	lista := []string{"5511999999999"}
	if !IsAllowed(lista, "5511999999999") {
		t.Fatal("número exato deveria passar")
	}
	if IsAllowed(lista, "5511888888888") {
		t.Fatal("número fora da lista não deveria passar")
	}
}

func TestIsAllowedPorSufixo(t *testing.T) {
	// código do país diferente, mesmo número
	if !IsAllowed([]string{"999999999"}, "5511999999999") {
		t.Fatal("deveria comparar pelo sufixo")
	}
}

func TestIsAllowedNumeroVazio(t *testing.T) {
	if IsAllowed([]string{"5511999999999"}, "") {
		t.Fatal("número vazio não deveria passar")
	}
}

func TestConfigStorePersiste(t *testing.T) {
	dir := t.TempDir()
	store, err := NewConfigStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.SetAllowed([]string{"5511999999999", " 5511888888888 "}, false); err != nil {
		t.Fatal(err)
	}

	// relê do disco
	store2, err := NewConfigStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := store2.Allowed()
	if len(got) != 2 || got[0] != "5511999999999" || got[1] != "5511888888888" {
		t.Fatalf("config não persistiu: %+v", got)
	}

	// o arquivo existe
	if _, err := os.Stat(filepath.Join(dir, "config.json")); err != nil {
		t.Fatalf("config.json não foi criado: %v", err)
	}
}

func TestConfigStoreAcceptAll(t *testing.T) {
	store, _ := NewConfigStore(t.TempDir())
	if err := store.SetAllowed([]string{"5511999999999"}, true); err != nil {
		t.Fatal(err)
	}
	if len(store.Allowed()) != 0 {
		t.Fatal("acceptAll deveria liberar todo mundo")
	}
}

func TestOnlyDigits(t *testing.T) {
	if onlyDigits("+55 (11) 99999-9999") != "5511999999999" {
		t.Fatal("só dígitos falhou")
	}
	if onlyDigits("abc") != "" {
		t.Fatal("só dígitos falhou")
	}
}
