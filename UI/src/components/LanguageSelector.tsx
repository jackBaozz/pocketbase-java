import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { LANGUAGES } from '../i18n';
import { Globe } from 'lucide-react';

export const LanguageSelector: React.FC = () => {
  const { i18n, t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const changeLanguage = (lng: string) => {
    i18n.changeLanguage(lng);
    setIsOpen(false);
  };

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const currentLang = LANGUAGES.find(l => l.code === i18n.resolvedLanguage) || LANGUAGES[0];

  return (
    <div style={{ position: 'relative' }} ref={dropdownRef}>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="header-link"
        title={t("language.change", "Change language")}
      >
        <Globe size={16} />
        <span>{currentLang.label}</span>
      </button>

      {isOpen && (
        <div style={{
          position: 'absolute',
          right: 0,
          top: '100%',
          marginTop: '4px',
          zIndex: 100,
          background: 'var(--surfaceColor)',
          border: '1px solid var(--surfaceAlt2Color)',
          borderRadius: 'var(--borderRadius)',
          boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)',
          minWidth: '120px',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column'
        }}>
          {LANGUAGES.map((lang) => (
            <button
              key={lang.code}
              onClick={() => changeLanguage(lang.code)}
              style={{
                width: '100%',
                textAlign: 'left',
                background: i18n.resolvedLanguage === lang.code ? 'var(--surfaceAlt2Color)' : 'transparent',
                color: i18n.resolvedLanguage === lang.code ? 'var(--surfaceTxtColor)' : 'var(--surfaceTxtHintColor)',
                border: 'none',
                padding: '8px 16px',
                fontSize: '13px',
                cursor: 'pointer',
                transition: 'background 0.2s',
              }}
              onMouseEnter={(e) => {
                if (i18n.resolvedLanguage !== lang.code) {
                  e.currentTarget.style.background = 'var(--surfaceAlt1Color)';
                  e.currentTarget.style.color = 'var(--surfaceTxtColor)';
                }
              }}
              onMouseLeave={(e) => {
                if (i18n.resolvedLanguage !== lang.code) {
                  e.currentTarget.style.background = 'transparent';
                  e.currentTarget.style.color = 'var(--surfaceTxtHintColor)';
                }
              }}
            >
              {lang.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};
