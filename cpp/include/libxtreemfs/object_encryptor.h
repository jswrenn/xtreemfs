/*
 * Copyright (c) 2014 by Philippe Lieser, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

#ifndef CPP_INCLUDE_LIBXTREEMFS_OBJECT_ENCRYPTOR_H_
#define CPP_INCLUDE_LIBXTREEMFS_OBJECT_ENCRYPTOR_H_

#include <boost/function.hpp>
#include <boost/noncopyable.hpp>
#include <string>
#include <vector>

#include "libxtreemfs/file_key_distribution.h"
#include "libxtreemfs/file_info.h"
#include "libxtreemfs/hash_tree_ad.h"
#include "libxtreemfs/options.h"
#include "libxtreemfs/volume_implementation.h"
#include "util/crypto/cipher.h"
#include "util/crypto/sign_algorithm.h"

namespace xtreemfs {

/**
 * These are injected functions that provide partial read and write
 * functionality for objects.
 */
typedef boost::function<
    int(int object_no, char* buffer, int offset_in_object, int bytes_to_read)> PartialObjectReaderFunction;  // NOLINT
typedef boost::function<
    void(int object_no, const char* buffer, int offset_in_object,
         int bytes_to_write)> PartialObjectWriterFunction;

/**
 * Encrypts/Decrypts an object.
 */
class ObjectEncryptor : private boost::noncopyable {
 private:
  class FileLock;

 public:
  ObjectEncryptor(const pbrpc::UserCredentials& user_credentials,
                  const pbrpc::XCap& xcap, VolumeImplementation* volume,
                  FileInfo* file_info, int object_size);

  ~ObjectEncryptor();

  class Operation : private boost::noncopyable {
   public:
    Operation(ObjectEncryptor* obj_enc, bool write);

    int Read(int object_no, char* buffer, int offset_in_object,
             int bytes_to_read, PartialObjectReaderFunction reader);

    void Write(int object_no, const char* buffer, int offset_in_object,
               int bytes_to_write, PartialObjectReaderFunction reader,
               PartialObjectWriterFunction writer);

   protected:
    ObjectEncryptor* obj_enc_;

    const int& enc_block_size_;

    const int& object_size_;

    HashTreeAD hash_tree_;

    int64_t old_file_size_;

    boost::scoped_ptr<FileLock> operation_lock_;

    boost::scoped_ptr<FileLock> file_lock_;

   private:
    int EncryptEncBlock(int block_number, boost::asio::const_buffer plaintext,
                        boost::asio::mutable_buffer ciphertext);

    int DecryptEncBlock(int block_number, boost::asio::const_buffer ciphertext,
                        boost::asio::mutable_buffer plaintext);
  };

  class ReadOperation : public Operation {
   public:
    ReadOperation(ObjectEncryptor* obj_enc, int64_t offset, int count);
  };

  class WriteOperation : public Operation {
   public:
    WriteOperation(ObjectEncryptor* obj_enc, int64_t offset, int count,
                   PartialObjectReaderFunction reader,
                   PartialObjectWriterFunction writer);

    ~WriteOperation();
  };

  class TruncateOperation : public Operation {
   public:
    TruncateOperation(ObjectEncryptor* obj_enc,
                      const xtreemfs::pbrpc::UserCredentials& user_credentials,
                      int64_t new_file_size, PartialObjectReaderFunction reader,
                      PartialObjectWriterFunction writer);
  };

  void Flush();

  static bool IsEncMetaFile(const std::string& path);

  static void Unlink(const xtreemfs::pbrpc::UserCredentials& user_credentials,
                     VolumeImplementation* volume, uint64_t file_id);

 private:
  class FileLock : private boost::noncopyable {
   public:
    FileLock(ObjectEncryptor* obj_enc, uint64_t offset, uint64_t length,
             bool exclusive, bool wait_for_lock = true);

    void Change(uint64_t offset, uint64_t length);

    ~FileLock();

   private:
    FileHandle* file_;
    boost::scoped_ptr<pbrpc::Lock> lock_;
  };

  FileKeyDistribution key_distribution;

  std::vector<unsigned char> file_enc_key_;

  int enc_block_size_;

  Cipher cipher_;

  SignAlgorithm sign_algo_;

  /**
   * Object size in bytes.
   */
  int object_size_;

  FileInfo* file_info_;

  /**
   * File handle for the meta file. Not owned by this class. Close is called on
   * destruction.
   */
  FileHandle* meta_file_;

  /**
   * Reference to volume options. Not owned by class.
   */
  const Options& volume_options_;
};

}  // namespace xtreemfs

#endif  // CPP_INCLUDE_LIBXTREEMFS_OBJECT_ENCRYPTOR_H_
