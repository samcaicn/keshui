package com.example.drowze

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContactsActivity : AppCompatActivity() {
    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var nameEditText: EditText
    private lateinit var phoneEditText: EditText
    private lateinit var addContactButton: Button
    
    private lateinit var contacts: MutableList<Contact>
    private lateinit var adapter: ContactsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        
        // Initialize UI components
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        nameEditText = findViewById(R.id.nameEditText)
        phoneEditText = findViewById(R.id.phoneEditText)
        addContactButton = findViewById(R.id.addContactButton)
        
        // Load existing contacts
        contacts = Utils.getContacts(this).toMutableList()
        
        // Set up RecyclerView
        adapter = ContactsAdapter(contacts) { position ->
            removeContact(position)
        }
        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        contactsRecyclerView.adapter = adapter
        
        // Set up add contact button
        addContactButton.setOnClickListener {
            addContact()
        }
    }
    
    private fun addContact() {
        val name = nameEditText.text.toString().trim()
        val phone = phoneEditText.text.toString().trim()
        
        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!isValidPhoneNumber(phone)) {
            Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Add the new contact
        val newContact = Contact(name, phone)
        contacts.add(newContact)
        
        // Save contacts
        Utils.saveContacts(this, contacts)
        
        // Update UI
        adapter.notifyItemInserted(contacts.size - 1)
        
        // Clear input fields
        nameEditText.text.clear()
        phoneEditText.text.clear()
        
        Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show()
    }
    
    private fun removeContact(position: Int) {
        if (position < 0 || position >= contacts.size) return
        
        contacts.removeAt(position)
        
        // Save updated contacts
        Utils.saveContacts(this, contacts)
        
        // Update UI
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, contacts.size)
        
        Toast.makeText(this, "Contact removed", Toast.LENGTH_SHORT).show()
    }
    
    private fun isValidPhoneNumber(phone: String): Boolean {
        // Basic validation - can be enhanced
        return phone.matches(Regex("^\\+?[0-9]{10,15}$"))
    }
}