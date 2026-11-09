package main

import (
	"context"
	"encoding/base64"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
)

// contextTODO é um contexto de fundo para as chamadas da API.
func contextTODO() context.Context { return context.Background() }

// decodeBase64 decodifica base64 (com ou sem prefixo data URI).
func decodeBase64(v string) ([]byte, error) {
	if i := indexOf(v, ","); i >= 0 {
		v = v[i+1:]
	}
	return base64.StdEncoding.DecodeString(v)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func main() {
	dir := "./data"
	port := 8097
	phone := ""

	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--dir":
			if i+1 < len(args) {
				dir = args[i+1]
				i++
			}
		case "--port":
			if i+1 < len(args) {
				port, _ = strconv.Atoi(args[i+1])
				i++
			}
		case "--phone":
			if i+1 < len(args) {
				phone = args[i+1]
				i++
			}
		}
	}

	abs, err := dataDir(dir)
	if err != nil {
		log.Fatalf("wabridge: diretório inválido: %v", err)
	}

	cfg, err := NewConfigStore(abs)
	if err != nil {
		log.Fatalf("wabridge: configuração: %v", err)
	}

	wa, err := NewWhatsmeow(abs, phone)
	if err != nil {
		log.Fatalf("wabridge: motor do WhatsApp: %v", err)
	}
	defer wa.Close()

	if err := wa.Connect(contextTODO()); err != nil {
		log.Printf("wabridge: connect: %v", err)
	}

	srv := NewServer(wa, cfg, abs, port)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		log.Println("wabridge: encerrando")
		wa.Close()
		os.Exit(0)
	}()

	if err := srv.Run(); err != nil {
		log.Fatalf("wabridge: servidor: %v", err)
	}
}
