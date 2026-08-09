import React from 'react';

/**
 * Basic UI component to render record fields based on their type, providing specialized inputs
 * for SDP-012.
 */
export function FieldRenderer({ field, value, onChange }) {
    switch (field.type) {
        case 'bool':
            return (
                <div className="field-renderer">
                    <label>
                        <input 
                            type="checkbox" 
                            checked={!!value} 
                            onChange={e => onChange(field.name, e.target.checked)} 
                        />
                        {field.name}
                    </label>
                </div>
            );
        case 'number':
            return (
                <div className="field-renderer">
                    <label>{field.name}</label>
                    <input 
                        type="number" 
                        value={value || 0} 
                        onChange={e => onChange(field.name, Number(e.target.value))} 
                    />
                </div>
            );
        case 'json':
            return (
                <div className="field-renderer">
                    <label>{field.name} (JSON)</label>
                    <textarea 
                        rows={4}
                        value={typeof value === 'object' ? JSON.stringify(value, null, 2) : value || ''} 
                        onChange={e => {
                            try {
                                const parsed = JSON.parse(e.target.value);
                                onChange(field.name, parsed);
                            } catch {
                                onChange(field.name, e.target.value); // Wait until valid
                            }
                        }} 
                    />
                </div>
            );
        case 'date':
            return (
                <div className="field-renderer">
                    <label>{field.name}</label>
                    <input 
                        type="datetime-local" 
                        value={value || ''} 
                        onChange={e => onChange(field.name, e.target.value)} 
                    />
                </div>
            );
        case 'select':
            return (
                <div className="field-renderer">
                    <label>{field.name}</label>
                    <select value={value || ''} onChange={e => onChange(field.name, e.target.value)}>
                        <option value="">-- Select --</option>
                        {field.options?.values?.map(opt => (
                            <option key={opt} value={opt}>{opt}</option>
                        ))}
                    </select>
                </div>
            );
        default:
            return (
                <div className="field-renderer">
                    <label>{field.name}</label>
                    <input 
                        type="text" 
                        value={value || ''} 
                        onChange={e => onChange(field.name, e.target.value)} 
                    />
                </div>
            );
    }
}
